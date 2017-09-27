package org.phoebus.ui.application;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.phoebus.framework.persistence.MementoTree;
import org.phoebus.framework.persistence.XMLMementoTree;
import org.phoebus.framework.spi.AppDescriptor;
import org.phoebus.framework.spi.AppResourceDescriptor;
import org.phoebus.framework.spi.MenuEntry;
import org.phoebus.framework.util.ResourceParser;
import org.phoebus.framework.workbench.ApplicationService;
import org.phoebus.framework.workbench.MenuEntryService;
import org.phoebus.framework.workbench.MenuEntryService.MenuTreeNode;
import org.phoebus.framework.workbench.ResourceHandlerService;
import org.phoebus.framework.workbench.ToolbarEntryService;
import org.phoebus.ui.dialog.DialogHelper;
import org.phoebus.ui.dialog.OpenFileDialog;
import org.phoebus.ui.docking.DockPane;
import org.phoebus.ui.docking.DockStage;
import org.phoebus.ui.internal.MementoHelper;
import org.phoebus.ui.jobs.JobManager;
import org.phoebus.ui.jobs.JobMonitor;
import org.phoebus.ui.jobs.SubJobMonitor;
import org.phoebus.ui.welcome.Welcome;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Primary UI for a phoebus application
 *
 * <p>
 * Menu bar, tool bar, ..
 *
 * @author Kunal Shroff
 * @author Kay Kasemir
 */
@SuppressWarnings("nls")
public class PhoebusApplication extends Application {
    /** Logger for all application messages */
    public static final Logger logger = Logger.getLogger(PhoebusApplication.class.getName());

    /** Memento key to show/hide tabs */
    private static final String SHOW_TABS = "show_tabs";

    /** Menu item to show/hide tabs */
    private CheckMenuItem show_tabs;

    /** JavaFX entry point
     *  @param initial_stage Initial Stage created by JavaFX
     */
    @Override
    public void start(final Stage initial_stage) throws Exception {
        // Show splash screen as soon as possible..
        final Splash splash = new Splash(initial_stage);

        // .. then read saved state etc. in background job
        JobManager.schedule("Startup", monitor ->
        {
            final JobMonitor splash_monitor = new SplashJobMonitor(monitor, splash);
            backgroundStartup(splash_monitor, splash);
        });
    }

    /** Perform potentially slow startup task off the UI thread
     *  @param monitor
     *  @param splash
     *  @throws Exception
     */
    private void backgroundStartup(final JobMonitor monitor, final Splash splash)
    {
        // Assume there's 100 percent of work do to,
        // not knowing, yet, how many applications to start etc.
        monitor.beginTask("Start Applications", 100);

        // Locate registered applications and start them, allocating 30% to that
        startApplications(new SubJobMonitor(monitor, 30));

        // Load saved state (slow file access) off UI thread, allocating 30% to that
        monitor.beginTask("Load saved state");
        final MementoTree memento = loadMemento(new SubJobMonitor(monitor, 30));

        // Back to UI thread
        Platform.runLater(() ->
        {
            try
            {
                // Leaving remaining 40% to the UI startup
                startUI(memento, new SubJobMonitor(monitor, 40));
            }
            catch (Throwable ex)
            {
                logger.log(Level.SEVERE, "Application cannot start up", ex);
            }
            monitor.done();
            splash.close();
        });
    }

    private void startUI(final MementoTree memento, final JobMonitor monitor) throws Exception
    {
        monitor.beginTask("Start UI", 4);

        final Stage main_stage = new Stage();
        final MenuBar menuBar = createMenu(main_stage);
        final ToolBar toolBar = createToolbar();

        DockStage.configureStage(main_stage);
        // Patch ID of main window
        // (in case we ever need to identify the main window)
        main_stage.getProperties().put(DockStage.KEY_ID, DockStage.ID_MAIN);

        final BorderPane layout = DockStage.getLayout(main_stage);
        layout.setTop(new VBox(menuBar, toolBar));
        layout.setBottom(new Label("Status Bar..."));

        // Main stage may still be moved, resized, and restored apps are added.
        // --> Would be nice to _not_ show it, yet.
        // But restoreState will only find ID_MAIN when the window is visible
        // --> Do show it.
        main_stage.show();
        monitor.worked(1);

        // If there's nothing to restore from a previous instance,
        // start with welcome
        monitor.updateTaskName("Restore tabs");
        if (! restoreState(memento))
            new Welcome().create();
        monitor.worked(1);

        // Check command line parameters
        monitor.updateTaskName("Handle command line parameters");
        handleParameters(getParameters().getRaw());
        monitor.worked(1);

        // In 'server' mode, handle parameters received from client instances
        ApplicationServer.setOnReceivedArgument(this::handleClientParameters);

        // Closing the primary window is like calling File/Exit.
        // When the primary window is the only open stage, that's OK.
        // If there are other stages still open,
        // closing them all might be unexpected to the user,
        // so prompt for confirmation.
        main_stage.setOnCloseRequest(event -> {
            if (closeMainStage(main_stage))
                stop();
            // Else: At least one tab in one stage didn't want to close
            event.consume();
        });

        DockPane.setActiveDockPane(DockStage.getDockPane(main_stage));
        monitor.done();
    }

    /** Handle parameters from clients, logging errors
     *  @param parameters Command-line parameters from client
     */
    private void handleClientParameters(final List<String> parameters)
    {
        try
        {
            handleParameters(parameters);
        }
        catch (Exception ex)
        {
            logger.log(Level.SEVERE, "Cannot handle client parameters " + parameters, ex);
        }
    }

    /** Handle command line parameters
     *  @param parameters Command-line parameters
     *  @throws Exception on error
     */
    private void handleParameters(final List<String> parameters) throws Exception
    {
        // List of applications to launch as specified via cmd line args
        final List<String> launchApps = new ArrayList<String>();
        // List of resources to launch as specified via cmd line args
        final List<String> launchResources = new ArrayList<String>();
        final Iterator<String> parametersIterator = parameters.iterator();
        while (parametersIterator.hasNext()) {
            final String cmd = parametersIterator.next();
            if (cmd.equals("-app")) {
                if (!parametersIterator.hasNext())
                    throw new Exception("Missing -app application name");
                final String filename = parametersIterator.next();
                launchApps.add(filename);
            } else if (cmd.equals("-resource")) {
                if (!parametersIterator.hasNext())
                    throw new Exception("Missing -resource resource file name");
                final String filename = parametersIterator.next();
                launchResources.add(filename);
            }
        }

        // May have been invoked from background thread,
        // but application UIs need to open on UI thread
        Platform.runLater(() ->
        {
            // Handle requests to open resource from command line
            for (String resource : launchResources)
                openResource(resource);

            // Handle requests to open resource from command line
            for (String appLaunchString : launchApps)
                launchApp(appLaunchString);
        });
    }

    private MenuBar createMenu(final Stage stage) {
        final MenuBar menuBar = new MenuBar();

        // File
        final MenuItem open = new MenuItem("Open");
        open.setOnAction(event ->
        {
            final File file = new OpenFileDialog().promptForFile(stage, "Open File", null, null);
            if (file == null)
                return;
            openResource(file.toString());
        });
        final MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(event -> {
            if (closeMainStage(null))
                stop();
        });
        menuBar.getMenus().add(new Menu("File", null, open, exit));


        // Application Contributions
        final Menu applicationsMenu = new Menu("Applications");
        MenuTreeNode node = MenuEntryService.getInstance().getMenuEntriesTree();
        addMenuNode(applicationsMenu, node);
        menuBar.getMenus().add(applicationsMenu);


        show_tabs = new CheckMenuItem("Always Show Tabs");
        show_tabs.setSelected(DockPane.isAlwaysShowingTabs());
        show_tabs.setOnAction(event ->  DockPane.alwaysShowTabs(show_tabs.isSelected()));
        menuBar.getMenus().add(new Menu("Window", null, show_tabs));


        // Help
        final MenuItem content = new MenuItem("Content");
        content.setOnAction(event ->
        {
            final Alert todo = new Alert(AlertType.INFORMATION);
            todo.setHeaderText("Help Content");
            todo.setContentText("We indeed need somebody who writes online help");
            DialogHelper.positionDialog(todo, stage.getScene().getRoot(), 0, 0);
            todo.showAndWait();
        });
        menuBar.getMenus().add(new Menu("Help", null, content));

        return menuBar;
    }

    private void addMenuNode(Menu parent, MenuTreeNode node) {

        for (MenuEntry entry : node.getMenuItems()) {
            MenuItem m = new MenuItem(entry.getName());
            m.setOnAction((event) -> {
                try {
                    entry.call();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Error invoking menu " + entry.getName(), ex);
                }
            });
            parent.getItems().add(m);
        }

        for (MenuTreeNode child : node.getChildren()) {
            Menu childMenu = new Menu(child.getName());
            addMenuNode(childMenu, child);
            parent.getItems().add(childMenu);
        }
    }

    private ToolBar createToolbar() {
        final ToolBar toolBar = new ToolBar();

        // Contributed Entries
        ToolbarEntryService.getInstance().listToolbarEntries().forEach((entry) -> {
            final AtomicBoolean open_new = new AtomicBoolean();

            final Button button = new Button(entry.getName());

            // Want to handle button presses with 'Control' in different way,
            // but action event does not carry key modifier information.
            // -> Add separate event filter to remember the 'Control' state.
            button.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                open_new.set(event.isControlDown());
                // Still allow the button to react by 'arming' it
                button.arm();
            });

            button.setOnAction((event) -> {
                try {
                    // Future<?> future = executor.submit(entry.getActions());

                    if (open_new.get()) { // Invoke with new stage
                        final Window existing = DockPane.getActiveDockPane().getScene().getWindow();

                        final Stage new_stage = new Stage();
                        DockStage.configureStage(new_stage);
                        entry.call();
                        // Position near but not exactly on top of existing stage
                        new_stage.setX(existing.getX() + 10.0);
                        new_stage.setY(existing.getY() + 10.0);
                        new_stage.show();
                    } else
                        entry.call();
                } catch (Exception ex) {
                    logger.log(Level.WARNING, "Error invoking toolbar " + entry.getName(), ex);
                }
            });

            toolBar.getItems().add(button);
        });
        toolBar.setPrefWidth(600);
        return toolBar;
    }

    /**
     * @param resource Resource received as command line argument
     */
    private void openResource(final String resource) {
        List<AppResourceDescriptor> applications = ResourceHandlerService.getApplications(resource);
        if (applications.isEmpty()) {
            logger.log(Level.WARNING, "No application found for opening " + resource);
        } else {
            final AppResourceDescriptor application;
            if (applications.size() == 1)
                application = applications.get(0);
            else
            {   // Prompt user which application to use for this resource
                final List<String> options = applications.stream().map(app -> app.getDisplayName()).collect(Collectors.toList());
                final ChoiceDialog<String> which = new ChoiceDialog<>(options.get(0), options);
                which.setTitle("Open");
                which.setHeaderText("Select application for opening\n" + resource);
                final Optional<String> result = which.showAndWait();
                if (! result.isPresent())
                    return;
                application = applications.get(options.indexOf(result.get()));
            }

            final String app_resource = application.getName() + "?" + ResourceParser.FILE_ARG + "=" + resource;
            logger.log(Level.INFO, "Opening " + app_resource);
            application.create(app_resource);
        }
    }

    /**
     * Launch applications with
     *
     * @param appLaunchString
     *            application launch string received as command line argument which
     *            contains the app name and the arguments that the applications
     *            should be launched with , this has to be in the format of a valid
     *            URL. e.g. probe?pv=sim://noise&pv=sim://ramp
     */
    private void launchApp(final String appLaunchString) {
        String appName = ResourceParser.parseAppName(appLaunchString);
        final AppDescriptor app = ApplicationService.findApplication(appName);
        if (app == null)
        {
            logger.log(Level.SEVERE, "Unknown application '" + appName + "'");
            return;
        }
        if (app instanceof AppResourceDescriptor)
        {
            ((AppResourceDescriptor)app).create(appLaunchString);
        } else
        {
            app.create();
        }
    }

    /** @param monitor
     *  @return Memento for previously persisted state or <code>null</code> */
    private MementoTree loadMemento(final JobMonitor monitor)
    {
        monitor.beginTask("Load persisted state", 1);
        final File memfile = XMLMementoTree.getDefaultFile();
        try
        {
            if (memfile.canRead())
            {
                logger.log(Level.INFO, "Loading state from " + memfile);
                return XMLMementoTree.read(new FileInputStream(memfile));
            }
        }
        catch (Exception ex)
        {
            logger.log(Level.SEVERE, "Error restoring saved state from " + memfile, ex);
        }
        finally
        {
            monitor.done();
        }
        return null;
    }

    /** Restore stages from memento
     *  @return <code>true</code> if any tab was restored
     */
    private boolean restoreState(final MementoTree memento) {
        boolean any = false;

        if (memento == null)
            return any;

        try {
            // Global settings
            memento.getBoolean(SHOW_TABS).ifPresent(show ->
            {
                DockPane.alwaysShowTabs(show);
                show_tabs.setSelected(show);
            });

            // Settings for each stage
            for (MementoTree stage_memento : memento.getChildren()) {
                final String id = stage_memento.getName();
                Stage stage = DockStage.getDockStageByID(id);
                if (stage == null) {
                    // Create new Stage with that ID
                    stage = new Stage();
                    DockStage.configureStage(stage);
                    stage.getProperties().put(DockStage.KEY_ID, id);
                    stage.show();
                }

                any |= MementoHelper.restoreStage(stage_memento, stage);
            }
        } catch (Throwable ex) {
            logger.log(Level.WARNING, "Error restoring saved state", ex);
        }
        return any;
    }

    /** Save state of all stages to memento */
    private void saveState() {
        final File memfile = XMLMementoTree.getDefaultFile();
        logger.log(Level.INFO, "Persisting state to " + memfile);
        try {
            final XMLMementoTree memento = XMLMementoTree.create();

            memento.setBoolean(SHOW_TABS, DockPane.isAlwaysShowingTabs());

            for (Stage stage : DockStage.getDockStages())
                MementoHelper.saveStage(memento, stage);

            if (!memfile.getParentFile().exists())
                memfile.getParentFile().mkdirs();
            memento.write(new FileOutputStream(memfile));
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Error writing saved state to " + memfile, ex);
        }
    }

    /**
     * Close the main stage
     *
     * <p>
     * If there are more stages open, warn user that they will be closed.
     *
     * <p>
     * When called from the onCloseRequested handler of the primary stage, we must
     * _not_ send another close request to it because that would create an infinite
     * loop.
     *
     * @param main_stage_already_closing
     *            Primary stage when called from its onCloseRequested handler, else
     *            <code>null</code>
     * @return
     */
    private boolean closeMainStage(final Stage main_stage_already_closing) {
        final List<Stage> stages = DockStage.getDockStages();

        if (stages.size() > 1) {
            final Alert dialog = new Alert(AlertType.CONFIRMATION);
            dialog.setTitle("Exit Phoebus");
            dialog.setHeaderText("Close main window");
            dialog.setContentText("Closing this window exits the application,\nclosing all other windows.\n");
            DialogHelper.positionDialog(dialog, stages.get(0).getScene().getRoot(), -200, -200);
            if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
                return false;
        }

        // If called from the main stage that's already about to close,
        // skip that one when closing all stages
        if (main_stage_already_closing != null)
            stages.remove(main_stage_already_closing);

        if (!closeStages(stages))
            return false;

        // Once all other stages are closed,
        // potentially check the main stage.
        if (main_stage_already_closing != null && !DockStage.isStageOkToClose(main_stage_already_closing))
            return false;
        return true;
    }

    /**
     * Close several stages
     *
     * @param stages_to_check
     *            Stages that will be asked to close
     * @return <code>true</code> if all stages closed, <code>false</code> if one
     *         stage didn't want to close.
     */
    private boolean closeStages(final List<Stage> stages_to_check) {
        // Save current state, _before_ tabs are closed and thus
        // there's nothing left to save
        saveState();

        for (Stage stage : stages_to_check) {
            // Could close via event, but then still need to check if the stage remained
            // open
            // stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            if (DockStage.isStageOkToClose(stage))
                stage.close();
            else
                return false;
        }
        return true;
    }

    /**
     * Start all applications
     * @param monitor
     */
    private void startApplications(final JobMonitor monitor)
    {
        final Collection<AppDescriptor> apps = ApplicationService.getApplications();
        monitor.beginTask("Start applications", apps.size());
        for (AppDescriptor app : apps)
        {
            monitor.updateTaskName("Starting " + app.getDisplayName());
            app.start();
            monitor.worked(1);

            // TODO Remove dummy delay
            try { Thread.sleep(100); } catch (InterruptedException ex) {}
        }
    }

    /**
     * Stop all applications
     */
    private void stopApplications() {
        for (AppDescriptor app : ApplicationService.getApplications())
            app.stop();
    }

    @Override
    public void stop() {
        stopApplications();

        // Hard exit because otherwise background threads
        // might keep us from quitting the VM
        logger.log(Level.INFO, "Exiting");
        System.exit(0);
    }
}