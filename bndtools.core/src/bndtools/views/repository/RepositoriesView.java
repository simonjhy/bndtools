package bndtools.views.repository;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bndtools.api.ILogger;
import org.bndtools.api.Logger;
import org.bndtools.core.ui.icons.Icons;
import org.bndtools.utils.Function;
import org.bndtools.utils.swt.FilterPanelPart;
import org.bndtools.utils.swt.SWTConcurrencyUtil;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.GroupMarker;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.DoubleClickEvent;
import org.eclipse.jface.viewers.IDoubleClickListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerDropAdapter;
import org.eclipse.jface.window.Window;
import org.eclipse.jface.wizard.WizardDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.dnd.TransferData;
import org.eclipse.swt.dnd.URLTransfer;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IWorkbenchActionConstants;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.ResourceTransfer;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.ServiceRegistration;
import org.osgi.resource.Requirement;

import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Jar;
import aQute.bnd.service.Actionable;
import aQute.bnd.service.Refreshable;
import aQute.bnd.service.RepositoryListenerPlugin;
import aQute.bnd.service.RepositoryPlugin;
import aQute.lib.converter.Converter;
import aQute.lib.io.IO;
import bndtools.Activator;
import bndtools.Plugin;
import bndtools.central.Central;
import bndtools.central.RepositoryUtils;
import bndtools.model.repo.ContinueSearchElement;
import bndtools.model.repo.RepositoryBundle;
import bndtools.model.repo.RepositoryBundleVersion;
import bndtools.model.repo.RepositoryTreeLabelProvider;
import bndtools.model.repo.SearchableRepositoryTreeContentProvider;
import bndtools.preferences.JpmPreferences;
import bndtools.utils.SelectionDragAdapter;
import bndtools.wizards.workspace.AddFilesToRepositoryWizard;

public class RepositoriesView extends ViewPart implements RepositoryListenerPlugin {
    final static Pattern LABEL_PATTERN = Pattern.compile("(-)?(!)?([^{}]+)(?:\\{([^}]+)\\})?");
    private static final String DROP_TARGET = "dropTarget";

    private static final ILogger logger = Logger.getLogger(RepositoriesView.class);

    private final FilterPanelPart filterPart = new FilterPanelPart(Plugin.getDefault().getScheduler());
    private final SearchableRepositoryTreeContentProvider contentProvider = new SearchableRepositoryTreeContentProvider();
    private TreeViewer viewer;

    private Action collapseAllAction;
    private Action refreshAction;
    private Action advancedSearchAction;

    private ServiceRegistration<RepositoryListenerPlugin> registration;

    private Set<String> expandedRepoNames = null;

    @Override
    public void createPartControl(Composite parent) {
        // CREATE CONTROLS
        Composite mainPanel = new Composite(parent, SWT.NONE);
        Control filterPanel = filterPart.createControl(mainPanel, 5, 5);
        Tree tree = new Tree(mainPanel, SWT.FULL_SELECTION | SWT.MULTI);
        filterPanel.setBackground(tree.getBackground());

        viewer = new TreeViewer(tree);
        viewer.setContentProvider(contentProvider);
        ColumnViewerToolTipSupport.enableFor(viewer);

        viewer.setLabelProvider(new RepositoryTreeLabelProvider(false));
        getViewSite().setSelectionProvider(viewer);

        createActions();

        JpmPreferences jpmPrefs = new JpmPreferences();
        final boolean showJpmOnClick = jpmPrefs.getBrowserSelection() != JpmPreferences.PREF_BROWSER_EXTERNAL;

        // LISTENERS
        filterPart.addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent event) {
                String filter = (String) event.getNewValue();
                updatedFilter(filter);
            }
        });
        ViewerDropAdapter dropAdapter = new ViewerDropAdapter(viewer) {
            @Override
            public boolean validateDrop(Object target, int operation, TransferData transferType) {
                if (canDrop(target, transferType))
                    return true;

                boolean valid = false;
                if (target instanceof RepositoryPlugin) {
                    if (((RepositoryPlugin) target).canWrite()) {

                        if (URLTransfer.getInstance().isSupportedType(transferType))
                            return true;

                        if (LocalSelectionTransfer.getTransfer().isSupportedType(transferType)) {
                            ISelection selection = LocalSelectionTransfer.getTransfer().getSelection();
                            if (selection instanceof IStructuredSelection) {
                                for (Iterator< ? > iter = ((IStructuredSelection) selection).iterator(); iter.hasNext();) {
                                    Object element = iter.next();
                                    if (element instanceof RepositoryBundle || element instanceof RepositoryBundleVersion) {
                                        valid = true;
                                        break;
                                    }
                                    if (element instanceof IFile) {
                                        valid = true;
                                        break;
                                    }
                                    if (element instanceof IAdaptable) {
                                        IFile file = (IFile) ((IAdaptable) element).getAdapter(IFile.class);
                                        if (file != null) {
                                            valid = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        } else {
                            valid = true;
                        }
                    }
                }
                return valid;
            }

            @Override
            public void dragEnter(DropTargetEvent event) {
                super.dragEnter(event);
                event.detail = DND.DROP_COPY;
            }

            @Override
            public boolean performDrop(Object data) {
                if (RepositoriesView.this.performDrop(getCurrentTarget(), getCurrentEvent().currentDataType)) {
                    viewer.refresh(getCurrentTarget(), true);
                    return true;
                }

                boolean copied = false;
                if (URLTransfer.getInstance().isSupportedType(getCurrentEvent().currentDataType)) {
                    try {
                        URL url = new URL((String) URLTransfer.getInstance().nativeToJava(getCurrentEvent().currentDataType));
                        File tmp = File.createTempFile("dwnl", ".jar");
                        IO.copy(url, tmp);
                        copied = addFilesToRepository((RepositoryPlugin) getCurrentTarget(), new File[] {
                                tmp
                        });
                    } catch (Exception e) {
                        return false;
                    }
                } else if (data instanceof String[]) {
                    String[] paths = (String[]) data;
                    File[] files = new File[paths.length];
                    for (int i = 0; i < paths.length; i++) {
                        files[i] = new File(paths[i]);
                    }
                    copied = addFilesToRepository((RepositoryPlugin) getCurrentTarget(), files);
                } else if (data instanceof IResource[]) {
                    IResource[] resources = (IResource[]) data;
                    File[] files = new File[resources.length];
                    for (int i = 0; i < resources.length; i++) {
                        files[i] = resources[i].getLocation().toFile();
                    }
                    copied = addFilesToRepository((RepositoryPlugin) getCurrentTarget(), files);
                } else if (data instanceof IStructuredSelection) {
                    File[] files = convertSelectionToFiles((IStructuredSelection) data);
                    if (files != null && files.length > 0)
                        copied = addFilesToRepository((RepositoryPlugin) getCurrentTarget(), files);
                }
                return copied;
            }
        };
        dropAdapter.setFeedbackEnabled(false);
        dropAdapter.setExpandEnabled(false);

        viewer.addDropSupport(DND.DROP_COPY | DND.DROP_MOVE, new Transfer[] {
                URLTransfer.getInstance(), FileTransfer.getInstance(), ResourceTransfer.getInstance(), LocalSelectionTransfer.getTransfer()
        }, dropAdapter);
        viewer.addDragSupport(DND.DROP_COPY | DND.DROP_MOVE, new Transfer[] {
                LocalSelectionTransfer.getTransfer()
        }, new SelectionDragAdapter(viewer));

        viewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                boolean writableRepoSelected = false;
                IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
                Object element = selection.getFirstElement();
                if (element instanceof RepositoryPlugin) {
                    RepositoryPlugin repo = (RepositoryPlugin) element;
                    writableRepoSelected = repo.canWrite();
                }
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent ev) {
                Object element = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
                if (element instanceof ContinueSearchElement) {
                    try {
                        getViewSite().getPage().showView(Plugin.JPM_BROWSER_VIEW_ID, null, showJpmOnClick ? IWorkbenchPage.VIEW_ACTIVATE : IWorkbenchPage.VIEW_CREATE);
                    } catch (PartInitException e) {
                        Plugin.getDefault().getLog().log(e.getStatus());
                    }
                }
            }
        });
        viewer.addDoubleClickListener(new IDoubleClickListener() {
            @Override
            public void doubleClick(DoubleClickEvent event) {
                if (!event.getSelection().isEmpty()) {
                    IStructuredSelection selection = (IStructuredSelection) event.getSelection();
                    Object element = selection.getFirstElement();
                    if (element instanceof IAdaptable) {
                        URI uri = (URI) ((IAdaptable) element).getAdapter(URI.class);
                        if (uri != null) {
                            IWorkbenchPage page = getSite().getPage();
                            try {
                                IFileStore fileStore = EFS.getLocalFileSystem().getStore(uri);
                                IDE.openEditorOnFileStore(page, fileStore);
                            } catch (PartInitException e) {
                                logger.logError("Error opening editor for " + uri, e);
                            }
                        }
                    } else if (element instanceof ContinueSearchElement) {
                        ContinueSearchElement searchElement = (ContinueSearchElement) element;
                        try {
                            JpmPreferences jpmPrefs = new JpmPreferences();
                            if (jpmPrefs.getBrowserSelection() == JpmPreferences.PREF_BROWSER_EXTERNAL) {
                                URI browseUrl = searchElement.browse();
                                getViewSite().getWorkbenchWindow().getWorkbench().getBrowserSupport().getExternalBrowser().openURL(browseUrl.toURL());
                            } else
                                getViewSite().getPage().showView(Plugin.JPM_BROWSER_VIEW_ID, null, IWorkbenchPage.VIEW_VISIBLE);
                        } catch (PartInitException e) {
                            Plugin.getDefault().getLog().log(e.getStatus());
                        } catch (Exception e) {
                            Plugin.getDefault().getLog().log(new Status(IStatus.ERROR, Plugin.PLUGIN_ID, 0, "Failed to load repository browser view", e));
                        }
                    }

                }
            }
        });

        createContextMenu();

        // LOAD
        Central.onWorkspaceInit(new Function<Workspace,Void>() {
            @Override
            public Void run(Workspace a) {
                final List<RepositoryPlugin> repositories = RepositoryUtils.listRepositories(true);
                SWTConcurrencyUtil.execForControl(viewer.getControl(), true, new Runnable() {
                    @Override
                    public void run() {
                        viewer.setInput(repositories);
                    }
                });
                return null;
            }
        });

        // LAYOUT
        GridLayout layout = new GridLayout(1, false);
        layout.horizontalSpacing = 0;
        layout.verticalSpacing = 0;
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        mainPanel.setLayout(layout);

        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        filterPanel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

        // Toolbar
        createActions();
        fillToolBar(getViewSite().getActionBars().getToolBarManager());

        // Register as repository listener
        registration = Activator.getDefault().getBundleContext().registerService(RepositoryListenerPlugin.class, this, null);
    }

    @Override
    public void setFocus() {
        filterPart.setFocus();
    }

    private static File[] convertSelectionToFiles(ISelection selection) {
        if (!(selection instanceof IStructuredSelection))
            return new File[0];

        IStructuredSelection structSel = (IStructuredSelection) selection;
        List<File> files = new ArrayList<File>(structSel.size());

        for (Iterator< ? > iter = structSel.iterator(); iter.hasNext();) {
            Object element = iter.next();
            if (element instanceof IFile)
                files.add(((IFile) element).getLocation().toFile());
            else if (element instanceof IAdaptable) {
                IAdaptable adaptable = (IAdaptable) element;
                IFile ifile = (IFile) adaptable.getAdapter(IFile.class);
                if (ifile != null) {
                    files.add(ifile.getLocation().toFile());
                } else {
                    File file = (File) adaptable.getAdapter(File.class);
                    if (file != null) {
                        files.add(file);
                    }
                }
            }
        }

        return files.toArray(new File[files.size()]);
    }

    @Override
    public void dispose() {
        registration.unregister();
        super.dispose();
    }

    boolean addFilesToRepository(RepositoryPlugin repo, File[] files) {
        AddFilesToRepositoryWizard wizard = new AddFilesToRepositoryWizard(repo, files);
        WizardDialog dialog = new WizardDialog(getViewSite().getShell(), wizard);
        dialog.open();
        viewer.refresh(repo);
        return true;
    }

    private void updatedFilter(String filterString) {
        contentProvider.setFilter(filterString);
        viewer.refresh();
        if (filterString != null)
            viewer.expandToLevel(2);
    }

    void createActions() {
        collapseAllAction = new Action() {
            @Override
            public void run() {
                viewer.collapseAll();
            }
        };
        collapseAllAction.setText("Collapse All");
        collapseAllAction.setToolTipText("Collapse All");
        collapseAllAction.setImageDescriptor(AbstractUIPlugin.imageDescriptorFromPlugin(Plugin.PLUGIN_ID, "icons/collapseall.gif"));

        refreshAction = new Action() {
            @Override
            public void run() {
                doRefresh();
            }
        };
        refreshAction.setText("Refresh");
        refreshAction.setToolTipText("Refresh Repositories Tree");
        refreshAction.setImageDescriptor(AbstractUIPlugin.imageDescriptorFromPlugin(Plugin.PLUGIN_ID, "icons/arrow_refresh.png"));

        advancedSearchAction = new Action("Advanced Search", Action.AS_CHECK_BOX) {
            @Override
            public void run() {
                if (advancedSearchAction.isChecked()) {
                    AdvancedSearchDialog dialog = new AdvancedSearchDialog(getSite().getShell());
                    if (Window.OK == dialog.open()) {
                        Requirement req = dialog.getRequirement();
                        contentProvider.setRequirementFilter(req);
                        viewer.refresh();
                        viewer.expandToLevel(2);
                    } else {
                        advancedSearchAction.setChecked(false);
                    }
                } else {
                    contentProvider.setRequirementFilter(null);
                    viewer.refresh();
                }
            }
        };
        advancedSearchAction.setText("Advanced Search");
        advancedSearchAction.setToolTipText("Toggle Advanced Search");
        advancedSearchAction.setImageDescriptor(Icons.desc("search"));
    }

    void createContextMenu() {
        MenuManager mgr = new MenuManager();
        Menu menu = mgr.createContextMenu(viewer.getControl());
        viewer.getControl().setMenu(menu);
        mgr.add(new GroupMarker(IWorkbenchActionConstants.MB_ADDITIONS));
        getSite().registerContextMenu(mgr, viewer);

        mgr.addMenuListener(new IMenuListener() {

            @Override
            public void menuAboutToShow(IMenuManager manager) {
                try {
                    manager.removeAll();
                    IStructuredSelection selection = (IStructuredSelection) viewer.getSelection();
                    if (!selection.isEmpty()) {
                        final Object firstElement = selection.getFirstElement();
                        if (firstElement instanceof Actionable) {

                            final RepositoryPlugin rp = getRepositoryPlugin(firstElement);

                            //
                            // Use the Actionable interface to fill the menu
                            // Should extend this to allow other menu entries
                            // from the view, but currently there are none
                            //
                            final Actionable act = (Actionable) firstElement;
                            Map<String,Runnable> actions = act.actions();
                            if (actions != null) {
                                for (final Entry<String,Runnable> e : actions.entrySet()) {
                                    String label = e.getKey();
                                    boolean enabled = true;
                                    boolean checked = false;
                                    String description = null;
                                    Matcher m = LABEL_PATTERN.matcher(label);
                                    if (m.matches()) {
                                        if (m.group(1) != null)
                                            enabled = false;

                                        if (m.group(2) != null)
                                            checked = true;

                                        label = m.group(3);

                                        description = m.group(4);
                                    }
                                    Action a = new Action(label) {
                                        @Override
                                        public void run() {
                                            try {
                                                e.getValue().run();
                                                if (rp != null && rp instanceof Refreshable)
                                                    Central.refreshPlugin((Refreshable) rp);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                            viewer.refresh();
                                        }
                                    };
                                    a.setEnabled(enabled);
                                    if (description != null)
                                        a.setDescription(description);
                                    a.setChecked(checked);
                                    manager.add(a);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

        });
    }

    private void doRefresh() {
        // [cs] If a refresh is already happening, skip this one.
        if (expandedRepoNames != null) {
            return;
        }

        // Remember names of expanded repositories
        expandedRepoNames = new HashSet<String>();
        Object[] expandedElems = viewer.getExpandedElements();
        for (Object expandedElem : expandedElems) {
            if (expandedElem instanceof RepositoryPlugin) {
                expandedRepoNames.add(((RepositoryPlugin) expandedElem).getName());
            }
        }

        // kick off background thread for part 2.
        new DoRefresh2().schedule();
    }

    private class DoRefresh2 extends WorkspaceJob {

        public DoRefresh2() {
            super("Refresh repositories");
        }

        @Override
        public IStatus runInWorkspace(IProgressMonitor arg0) throws CoreException {
            //[cs] The refreshPlugins portion of doRefresh, doRefresh2, doRefresh3
            // was broken off to a background thread since this one executing on
            // the UI thread and freezing eclipse during some remote repository
            // downloads
            try {
                Central.refreshPlugins();
            } catch (Exception e) {
                logger.logError("While refreshing plugins", e);
            }
            SWTConcurrencyUtil.execForControl(viewer.getControl(), true, new Runnable() {
                @Override
                public void run() {
                    doRefresh3();
                }
            });
            return Status.OK_STATUS;
        }
    }

    private void doRefresh3() {
        // Reload repositories
        List<RepositoryPlugin> repos = RepositoryUtils.listRepositories(true);
        viewer.setInput(repos);

        // Expand any repos that have the same name as a repository that
        // was expanded before the reload.
        for (RepositoryPlugin repo : repos) {
            if (expandedRepoNames.contains(repo.getName())) {
                viewer.setExpandedState(repo, true);
            }
        }

        //[cs] Enable another refresh to happen
        expandedRepoNames = null;
    }

    private void fillToolBar(IToolBarManager toolBar) {
        toolBar.add(advancedSearchAction);
        toolBar.add(refreshAction);
        toolBar.add(collapseAllAction);
        toolBar.add(new Separator());
    }

    @Override
    public void bundleAdded(final RepositoryPlugin repository, Jar jar, File file) {
        if (viewer != null)
            SWTConcurrencyUtil.execForControl(viewer.getControl(), true, new Runnable() {
                @Override
                public void run() {
                    viewer.refresh(repository);
                }
            });
    }

    @Override
    public void bundleRemoved(final RepositoryPlugin repository, Jar jar, File file) {
        if (viewer != null)
            SWTConcurrencyUtil.execForControl(viewer.getControl(), true, new Runnable() {
                @Override
                public void run() {
                    viewer.refresh(repository);
                }
            });
    }

    @Override
    public void repositoryRefreshed(final RepositoryPlugin repository) {
        if (viewer != null)
            SWTConcurrencyUtil.execForControl(viewer.getControl(), true, new Runnable() {
                @Override
                public void run() {
                    viewer.refresh(repository);
                }
            });
    }

    @Override
    public void repositoriesRefreshed() {
        if (viewer != null && expandedRepoNames == null)
            SWTConcurrencyUtil.execForControl(viewer.getControl(), true, new Runnable() {
                @Override
                public void run() {
                    doRefresh();
                }
            });
    }

    /**
     * Handle the drop on targets that understand drops.
     *
     * @param target
     *            The current target
     * @param data
     *            The transfer data
     * @return true if the data is acceptable, otherwise false
     */
    boolean canDrop(Object target, TransferData data) {
        try {
            Class< ? > type = toJavaType(data);
            if (type != null) {
                target.getClass().getMethod(DROP_TARGET, type);
                return true;
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    /**
     * Try a drop on the target. A drop is allowed if the target implements a {@code dropTarget} method that returns a
     * boolean.
     *
     * @param target
     *            the target being dropped upon
     * @param data
     *            the data
     * @return true if dropped and processed, false if not
     */
    boolean performDrop(Object target, TransferData data) {
        try {
            Object java = toJava(data);
            if (java == null)
                return false;

            Method m = target.getClass().getMethod(DROP_TARGET, java.getClass());
            Object invoke = m.invoke(target, java);

            RepositoryPlugin repositoryPlugin = getRepositoryPlugin(target);
            if (repositoryPlugin != null && repositoryPlugin instanceof Refreshable)
                Central.refreshPlugin((Refreshable) repositoryPlugin);
            return invoke == null || invoke == Boolean.FALSE ? false : true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Return the class of the dropped object
     *
     * <pre>
     *    URLTransfer             URI
     *    FileTransfer            File[]
     *    TextTransfer            String
     *    ImageTransfer           ImageData
     * </pre>
     *
     * @param data
     *            the dropped object
     * @return the class of the dropped object, or null when it's unknown
     * @throws Exception
     *             upon error
     */
    Class< ? > toJavaType(TransferData data) throws Exception {
        if (URLTransfer.getInstance().isSupportedType(data))
            return URI.class;
        if (FileTransfer.getInstance().isSupportedType(data))
            return File[].class;
        if (TextTransfer.getInstance().isSupportedType(data))
            return String.class;
        //        if (ImageTransfer.getInstance().isSupportedType(data))
        //            return Image.class;
        return null;
    }

    /**
     * Return a native data type that represents the dropped object
     *
     * <pre>
     *    URLTransfer             URI
     *    FileTransfer            File[]
     *    TextTransfer            String
     *    ImageTransfer           ImageData
     * </pre>
     *
     * @param data
     *            the dropped object
     * @return a native data type that represents the dropped object, or null when the data type is unknown
     * @throws Exception
     *             upon error
     */
    Object toJava(TransferData data) throws Exception {
        if (URLTransfer.getInstance().isSupportedType(data))
            return Converter.cnv(URI.class, URLTransfer.getInstance().nativeToJava(data));
        else if (FileTransfer.getInstance().isSupportedType(data)) {
            return Converter.cnv(File[].class, FileTransfer.getInstance().nativeToJava(data));
        } else if (TextTransfer.getInstance().isSupportedType(data)) {
            return TextTransfer.getInstance().nativeToJava(data);
        }
        // Need to write the transfer code since the ImageTransfer turns it into
        // something very Eclipsy
        //        else if (ImageTransfer.getInstance().isSupportedType(data))
        //            return ImageTransfer.getInstance().nativeToJava(data);

        return null;
    }

    private RepositoryPlugin getRepositoryPlugin(Object element) {
        if (element instanceof RepositoryPlugin)
            return (RepositoryPlugin) element;
        else if (element instanceof RepositoryBundle)
            return ((RepositoryBundle) element).getRepo();
        else if (element instanceof RepositoryBundleVersion)
            return ((RepositoryBundleVersion) element).getBundle().getRepo();

        return null;
    }
}