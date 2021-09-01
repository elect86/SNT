/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.measure.Calibration;

import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.DirectedWeightedSubgraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.tracing.FillerThread;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SWCPoint;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class InteractiveTracerCanvas extends TracerCanvas {

	private static final long serialVersionUID = 1L;
	private final SNT tracerPlugin;
	private JPopupMenu pMenu;
	private JCheckBoxMenuItem toggleEditModeMenuItem;
	private JMenuItem extendPathMenuItem;
	private JCheckBoxMenuItem togglePauseTracingMenuItem;
	private JCheckBoxMenuItem togglePauseSNTMenuItem;
	private JCheckBoxMenuItem panMenuItem;
	private JMenuItem connectToSecondaryEditingPath;
	private double last_x_in_pane_precise = Double.MIN_VALUE;
	private double last_y_in_pane_precise = Double.MIN_VALUE;
	private boolean fillTransparent = false;
	private Path unconfirmedSegment;
	private Path currentPath;
	private boolean lastPathUnfinished;
	private boolean editMode; // convenience flag to monitor SNT's edit mode

	private Color temporaryColor;
	private Color unconfirmedColor;
	private Color fillColor;
	private GuiUtils guiUtils;
	protected static String EDIT_MODE_LABEL = "Edit Mode";
	protected static String SNT_PAUSED_LABEL = "SNT Paused";
	protected static String TRACING_PAUSED_LABEL = "Tracing Paused";

	protected InteractiveTracerCanvas(final ImagePlus imp,
	                                  final SNT plugin, final int plane,
	                                  final PathAndFillManager pathAndFillManager)
	{
		super(imp, plugin, plane, pathAndFillManager);
		tracerPlugin = plugin;
		buildPpupMenu();
		super.disablePopupMenu(true); // so that handlePopupMenu is not triggered
	}

	private void updateForkPointMenuItem(final JMenuItem forkNearestMenuItem) {
		// FIXME: We should be setting the accelerator to Alt+Shit+Button1. but KeyEvent.BUTTON1_MASK is never registered!?
		final String accelerator = (tracerPlugin.requireShiftToFork) ? "  (or Alt+Shift+Click) " : "  (or Alt+Click)";
		forkNearestMenuItem.setText( AListener.FORK_NEAREST + accelerator);
	}

	private void buildPpupMenu() {
		pMenu = new JPopupMenu();
		// Required because we are mixing lightweight and heavyweight components?
		pMenu.setLightWeightPopupEnabled(false);

		final AListener listener = new AListener();
		pMenu.add(menuItem(AListener.SELECT_NEAREST, listener, KeyEvent.VK_G));
		pMenu.add(menuItem(AListener.APPEND_NEAREST, listener, KeyStroke.getKeyStroke(KeyEvent.VK_G, KeyEvent.SHIFT_MASK)));
		final JMenuItem selectByRoi = new JMenuItem("Select Paths by 2D ROI");
		selectByRoi.addActionListener( e -> {
			if (pathAndFillManager.size() == 0) {
				getGuiUtils().error("There are no traced paths.", "Nothing to Select");
				return;
			}
			if (getImage().getRoi() != null && selectPathsByRoi()) {
				return; // a ROI existed and we successfully used it to select paths
			} else {
				// User still has to create ROI
				waitingForRoiDrawing = true;
				if (unsuitableToolForRoiSelection()) IJ.setTool("freehand");
				getGuiUtils().tempMsg("Draw ROI around paths to be selected. Current tool: " + IJ.getToolName());
			}
		});
		pMenu.add(selectByRoi);
		pMenu.addSeparator();

		final JMenuItem mi = menuItem(AListener.CLICK_AT_MAX, listener, KeyEvent.VK_V);
		mi.setEnabled(!tracerPlugin.is2D());
		pMenu.add(mi);
		extendPathMenuItem = menuItem(AListener.EXTEND_SELECTED, listener);
		pMenu.add(extendPathMenuItem);
		pMenu.add(menuItem(AListener.FORK_NEAREST, listener));
		pMenu.addSeparator();

		toggleEditModeMenuItem = new JCheckBoxMenuItem(AListener.EDIT_TOGGLE_FORMATTER);
		toggleEditModeMenuItem.addItemListener(listener);
		toggleEditModeMenuItem.setAccelerator(KeyStroke.getKeyStroke("shift E"));
		toggleEditModeMenuItem.setMnemonic(KeyEvent.VK_E);
		pMenu.add(toggleEditModeMenuItem);

		pMenu.add(menuItem(AListener.NODE_MOVE_Z, listener, KeyEvent.VK_B));
		pMenu.add(menuItem(AListener.NODE_DELETE, listener, KeyEvent.VK_D));
		pMenu.add(menuItem(AListener.NODE_INSERT, listener, KeyEvent.VK_I));
		pMenu.add(menuItem(AListener.NODE_LOCK, listener, KeyEvent.VK_L));
		pMenu.add(menuItem(AListener.NODE_MOVE, listener, KeyEvent.VK_M));
		pMenu.add(menuItem(AListener.NODE_SPLIT, listener, KeyEvent.VK_X));
		pMenu.addSeparator();

		connectToSecondaryEditingPath = menuItem(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER, listener);
		connectToSecondaryEditingPath.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0));
		connectToSecondaryEditingPath.setMnemonic(KeyEvent.VK_C);
		pMenu.add(connectToSecondaryEditingPath);
		pMenu.add(helpOnConnectingMenuItem());
		pMenu.addSeparator();

		pMenu.add(menuItem(AListener.NODE_RESET, listener));
		pMenu.add(menuItem(AListener.NODE_SET_ROOT, listener));
		pMenu.addSeparator();

		pMenu.add(menuItem(AListener.START_SHOLL, listener, 
				KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.SHIFT_MASK + KeyEvent.ALT_MASK)));
		final JMenuItem countSpines = new JMenuItem("Count Spine/Varicosities...");
		final boolean[] firstTimeCallingCountSpines = {true};
		countSpines.addActionListener(e -> {
			if (getPlane() != MultiDThreePanes.XY_PLANE) {
				getGuiUtils().error("Currently, counting Spine/Varicosities is only supported on main view.");
				return;
			}
			if (!isEventsDisabled())
				tracerPlugin.pause(true, true); // FIXME: We should support counting on side panes too
			if (isEventsDisabled()) { // plugin successfully paused
				IJ.setTool("multipoint");
				if (firstTimeCallingCountSpines[0]) showHelpOnCountingSpines();
				firstTimeCallingCountSpines[0] = false;
			}
		});
		pMenu.add(countSpines);
		pMenu.addSeparator();

		// Add a silly pan entry, just to remind users that the functionality exists.
		// TODO: Since we are going through the trouble, should it sync all panes?
		panMenuItem = new JCheckBoxMenuItem("Pan Mode  (or Hold Spacebar & Drag)", tracerPlugin.panMode);
		panMenuItem.addItemListener( e -> {
			tracerPlugin.panMode = panMenuItem.isSelected();
			disableEvents(tracerPlugin.panMode);
			if (tracerPlugin.panMode) {
				IJ.setKeyDown(KeyEvent.VK_SPACE);
			} else {
				IJ.setKeyUp(KeyEvent.VK_SPACE);
			}
		});
		pMenu.add(panMenuItem);
		togglePauseTracingMenuItem = new JCheckBoxMenuItem(AListener.PAUSE_TRACING_TOGGLE);
		togglePauseTracingMenuItem.setAccelerator(KeyStroke.getKeyStroke("shift P"));
		togglePauseTracingMenuItem.setMnemonic(KeyEvent.VK_P);
		togglePauseTracingMenuItem.addItemListener(listener);
		pMenu.add(togglePauseTracingMenuItem);
		togglePauseSNTMenuItem = new JCheckBoxMenuItem(AListener.PAUSE_SNT_TOGGLE);
		togglePauseSNTMenuItem.addItemListener(listener);
		pMenu.add(togglePauseSNTMenuItem);

	}

	private void showPopupMenu(final int x, final int y) {
		final Path activePath = tracerPlugin.getSingleSelectedPath();
		final boolean be = uiReadyForModeChange(SNTUI.EDITING);
		extendPathMenuItem.setText((activePath != null) ? "Continue Extending " + activePath
				.getName() : AListener.EXTEND_SELECTED);
		extendPathMenuItem.setEnabled(!(editMode || tracerPlugin.tracingHalted));
		toggleEditModeMenuItem.setEnabled(be);
		toggleEditModeMenuItem.setState(be && editMode);
		toggleEditModeMenuItem.setText(
				String.format(AListener.EDIT_TOGGLE_FORMATTER, (activePath == null) ? " Mode" : activePath.getName()));
		final boolean bp = uiReadyForModeChange(SNTUI.SNT_PAUSED);
		togglePauseSNTMenuItem.setEnabled(bp);
		togglePauseSNTMenuItem.setSelected(bp && tracerPlugin
			.getUIState() == SNTUI.SNT_PAUSED);
		togglePauseTracingMenuItem.setEnabled(!togglePauseSNTMenuItem.isSelected());
		togglePauseTracingMenuItem.setEnabled(bp);
		togglePauseTracingMenuItem.setSelected(tracerPlugin.tracingHalted);
		panMenuItem.setSelected(tracerPlugin.panMode);

		// Disable editing commands
		for (final MenuElement me : pMenu.getSubElements()) {
			if (me instanceof JMenuItem) {
				final JMenuItem mItem = ((JMenuItem) me);
				final String cmd = mItem.getActionCommand();

				if (cmd.startsWith(AListener.FORK_NEAREST)) {
					updateForkPointMenuItem(mItem);
				}

				if (togglePauseSNTMenuItem.isSelected() && !cmd.equals(
					AListener.PAUSE_SNT_TOGGLE))
				{
					mItem.setEnabled(false);
				}
				// commands only enabled in "Edit Mode"
				else if (cmd.equals(AListener.NODE_MOVE_Z)) {
					mItem.setEnabled(be && editMode && !tracerPlugin.is2D());
				}
				else if (cmd.equals(AListener.NODE_RESET) || cmd.equals(AListener.NODE_DELETE)
						|| cmd.equals(AListener.NODE_INSERT) || cmd.equals(AListener.NODE_LOCK)
						|| cmd.equals(AListener.NODE_MOVE) || cmd.equals(AListener.NODE_SET_ROOT)
						|| cmd.equals(AListener.NODE_SPLIT)) {
					mItem.setEnabled(be && editMode);
				} else {
					mItem.setEnabled(true);
				}
			}
		}
		updateConnectToSecondaryEditingPathMenuItem();
		pMenu.show(this, x, y);
	}

	protected void connectEditingPathToPreviousEditingPath() {
		// We need to check again here for two reasons.
		// 1) In case the user arrived here via the keyboard shortcut instead of the menu item
		// 2) It is possible for the editable node to change between the time the user opens the right-click menu
		//    and the moment they select the menu item
		if (!editMode || //
				tracerPlugin.getEditingPath() == null || //
				tracerPlugin.getPreviousEditingPath() == null || //
				tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() == -1) //
		{
			getGuiUtils().error(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + ": No connectable node exist.",
					"No Connectable Nodes");
			return;
		}
		final Path source = tracerPlugin.getEditingPath();
		connectToEditingPath(source, tracerPlugin.getPreviousEditingPath());
	}

	private void updateConnectToSecondaryEditingPathMenuItem() {
		if (!editMode || //
				tracerPlugin.getEditingPath() == null || //
				tracerPlugin.getPreviousEditingPath() == null || //
				tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() == -1) //
		{
			connectToSecondaryEditingPath.setText(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER);
			connectToSecondaryEditingPath.setEnabled(false);
			return;
		}
		connectToSecondaryEditingPath.setEnabled(true);
		final String label = tracerPlugin.getPreviousEditingPath().getName() + " (node " + tracerPlugin.getPreviousEditingPath().getEditableNodeIndex() +")";
		connectToSecondaryEditingPath.setText(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + label);
	}

	private void connectToEditingPath(final Path source, final Path destination) {
		if (source.getTreeID() == destination.getTreeID()) {
			getGuiUtils().error("Cannot connect selected nodes: A loop would be created!",
					"Reconstruction Cannot Contain Loops");
			return;
		}
		long start = System.currentTimeMillis();
		final Tree destinationTree = getTreeFromID(destination.getTreeID());
		final Tree sourceTree = getTreeFromID(source.getTreeID());
		final DirectedWeightedGraph destinationGraph = new DirectedWeightedGraph(destinationTree, false);
		// Source graph is merged into destination graph
		Graphs.addGraph(destinationGraph, new DirectedWeightedGraph(sourceTree, false));
		final SWCPoint destinationNode = getMatchingPointInGraph(destination.getNode(destination.getEditableNodeIndex()),
				destinationGraph);
		final SWCPoint sourceNode = getMatchingPointInGraph(source.getNode(source.getEditableNodeIndex()),
				destinationGraph);
		// Directed edge from destination node to source node
		destinationGraph.addEdge(destinationNode, sourceNode);
		final PointInImage destinationRoot = destinationTree.getRoot();
		// Set the correct edge directions in the merged graph
		destinationGraph.setRoot(getMatchingPointInGraph(destinationRoot, destinationGraph));
		final Tree newTree = destinationGraph.getTreeWithSamePathStructure();
		enableEditMode(false);
		final Calibration cal = tracerPlugin.getImagePlus().getCalibration(); // snt the instance of the plugin
		newTree.list().forEach(p -> p.setSpacing(cal));
		final boolean existingEnableUiUpdates = pathAndFillManager.enableUIupdates;
		pathAndFillManager.enableUIupdates = false;
		pathAndFillManager.deletePaths(destinationTree.list());
		pathAndFillManager.deletePaths(sourceTree.list());
		newTree.list().forEach(p -> pathAndFillManager.addPath(p, false, true));
		SNTUtils.log("Finished merge in " + (System.currentTimeMillis() - start) + "ms");
		pathAndFillManager.enableUIupdates = existingEnableUiUpdates;
	}

	private JMenuItem helpOnConnectingMenuItem() {
		final String msg = "<HTML>To connect two paths in <i>Edit Mode</i>:<ol>" +
			"  <li>Select parent path. If not yet editable, make it so by choosing <i><u>E</u>dit Path</i> (Shift+E)</li>" +
			"  <li>Select source node on parent path by hovering cursor over it</li>" +
			"  <li>Activate child path by pressing 'G' and select its destination node</li>" +
			"  <li>Link the two highlighted nodes by pressing 'C' (<u>C</u>onnect To... command)</li>" +
			"</ol>NB:<ol>" +
			"  <li>The direction of merge matters and it is assumed to be always from parent to child. " +
			"If child path is oriented in the wrong direction (i.e., moving “towards” its parent at the point " +
			"of merge), it will re-oriented so that single root connectivity is maintained</li>" +
			"  <li>Loop-forming connections are not allowed</li>" +
			"  <li>To concatenate or combine paths, use the respective commands in Path Manager's Edit menu</li>" +
			"</ol>";
		final JMenuItem helpItem = new JMenuItem(AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + "Help...");
		helpItem.addActionListener(e -> {
			final boolean canvasActivationState = tracerPlugin.autoCanvasActivation;
			tracerPlugin.enableAutoActivation(false); // this will not update the checkbox state in SNTUI, but 
													  // ensures the help dialog will maintain its frontmost state
			getGuiUtils().showHTMLDialog(msg, AListener.NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX + "Help", false)
					.addWindowListener(new WindowAdapter() {
						@Override
						public void windowClosing(final WindowEvent e) {
							tracerPlugin.enableAutoActivation(canvasActivationState);
						}
					});
		});
		return helpItem;
	}

	private JMenuItem menuItem(final String cmdName) {
		final JMenuItem mi = GuiUtils.menuItemWithoutAccelerator();
		mi.setText(cmdName);
		return mi;
	}

	private JMenuItem menuItem(final String cmdName, final ActionListener lstnr) {
		final JMenuItem mi = menuItem(cmdName);
		mi.addActionListener(lstnr);
		return mi;
	}

	private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final KeyStroke keystroke) {
		final JMenuItem mi = menuItem(cmdName, lstnr);
		mi.setAccelerator(keystroke);
		return mi;
	}

	private JMenuItem menuItem(final String cmdName, final ActionListener lstnr, final int keyEventKey) {
		final JMenuItem mi = menuItem(cmdName, lstnr, KeyStroke.getKeyStroke(keyEventKey, 0));
		mi.setMnemonic(keyEventKey);
		return mi;
	}

	public void setFillTransparent(final boolean transparent) {
		this.fillTransparent = transparent;
		if (transparent && fillColor != null) setFillColor(SNTColor.alphaColor(
			fillColor, 50));
	}

	public void setPathUnfinished(final boolean unfinished) {
		this.lastPathUnfinished = unfinished;
	}

	public void setTemporaryPath(final Path path) {
		this.unconfirmedSegment = path;
	}

	public void setCurrentPath(final Path path) {
		this.currentPath = path;
	}

	private boolean uiReadyForModeChange(final int mode) {
		if (!tracerPlugin.isUIready()) return false;
		return tracerPlugin.tracingHalted || tracerPlugin
			.getUIState() == SNTUI.WAITING_TO_START_PATH || tracerPlugin
				.getUIState() == mode;
	}

	public void toggleJustNearSlices() {
		just_near_slices = !just_near_slices;
	}

	protected void fakeMouseMoved(final boolean shift_pressed,
		final boolean join_modifier_pressed)
	{
		tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise,
			plane, shift_pressed, join_modifier_pressed);
	}

	protected void clickAtMaxPoint(final boolean join_modifier_pressed) {
		final int x = (int) Math.round(last_x_in_pane_precise);
		final int y = (int) Math.round(last_y_in_pane_precise);
		final int[] p = new int[3];
		tracerPlugin.findPointInStack(x, y, plane, p);
		tracerPlugin.clickAtMaxPoint(x, y, plane, join_modifier_pressed);
	}

	protected void startShollAnalysis() {
		PointInImage centerScaled = null;
		if (pathAndFillManager.anySelected()) {
			final double[] p = new double[3];
			tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
				last_y_in_pane_precise, plane, p);
			centerScaled = pathAndFillManager.nearestJoinPointOnSelectedPaths(p[0],
				p[1], p[2]);
		}
		else {
			final NearPoint np = getNearPointToMousePointer();
			if (np != null) {
				centerScaled = np.getNode();
			}
		}
		if (centerScaled == null) {
			getGuiUtils().tempMsg("No selectable nodes in view");
			return;
		}
		tracerPlugin.startSholl(centerScaled);
	}

	/** @deprecated */
	protected void selectNearestPathToMousePointerOld(
		final boolean addToExistingSelection)
	{
		if (pathAndFillManager.size() == 0) {
			getGuiUtils().tempMsg("Nothing to select: There are no traced paths");
			return;
		}
		final NearPoint nearPoint = getNearPointToMousePointer();
		if (nearPoint == null) {
			getGuiUtils().tempMsg("No selectable paths in view");
		}
		else {
			tracerPlugin.selectPath(nearPoint.getPath(), addToExistingSelection);
			getGuiUtils().tempMsg(nearPoint.getPath().getName() + " selected");
		}
	}

	public void selectNearestPathToMousePointer(
			final boolean addToExistingSelection)
	{
		if (pathAndFillManager.size() == 0) {
			getGuiUtils().tempMsg("Nothing to select: There are no traced paths");
			return;
		}

		List<PointInCanvas> nodes = new ArrayList<>();
		for (final Path path : pathAndFillManager.getPaths()) {
			if (!path.isSelected()) {
				nodes.addAll(path.getUnscaledNodesInViewPort(this));
			}
		}		
		if (nodes.isEmpty()) {
			getGuiUtils().tempMsg("Nothing to select. No paths in view");
			return;
		}

		final double[] p = new double[3];
		tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
			last_y_in_pane_precise, plane, p);
		PointInCanvas cursor = new PointInCanvas(p[0], p[1], 0);

		final NearPointInCanvas nearPoint = NearPointInCanvas.nearestPointInCanvas(nodes, cursor);
		if (nearPoint == null) {
			getGuiUtils().tempMsg("No selectable paths in view");
		}
		else {
			tracerPlugin.selectPath(nearPoint.getPath(), addToExistingSelection);
			getGuiUtils().tempMsg(nearPoint.getPath().getName() + " selected");
		}
	}

	private NearPoint getNearPointToMousePointer() {

		if (pathAndFillManager.size() == 0) {
			return null;
		}

//		System.out.println(last_x_in_pane_precise + ", " + last_y_in_pane_precise);
		final double[] p = new double[3];
		tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
			last_y_in_pane_precise, plane, p);

		// FIXME: We are going to ignore Z coordinates. Not sure why I
		// decided this was a good idea. Perhaps needs to be revised?
		final Rectangle rect = super.getSrcRect();
		final PointInImage rectMin = new PointInImage(rect.getMinX(), rect
			.getMinY(), 0);
		final PointInImage rectMax = new PointInImage(rect.getMaxX(), rect
			.getMaxY(), 0);
		final PointInImage cursor = new PointInImage(p[0], p[1], 0);
		final double maxSquaredLength = Math.max(cursor.distanceSquaredTo(rectMin),
			cursor.distanceSquaredTo(rectMax));
//		System.out.println(SNTUtils.formatDouble(last_x_in_pane_precise, 2) + ", " +
//			SNTUtils.formatDouble(last_y_in_pane_precise, 2) + " | dx:" + maxSquaredLength);

		// Find the nearest point on unselected Paths currently displayed in
		// viewPort
		final List<Path> paths = pathAndFillManager
			.getUnSelectedPathsRenderedInViewPort(this);
		if (paths.isEmpty()) {
			return null;
		}
		cursor.z = Double.NaN; // ignore Z-positioning of path nodes
		return pathAndFillManager.nearestPointOnAnyPath(paths, cursor,
			maxSquaredLength, true);
	}

	@Override
	public void setCursor(final int sx, final int sy, final int ox,
		final int oy)
	{
		if (isEventsDisabled() || !tracerPlugin.isUIready() || !cursorLocked)
			super.setCursor(sx, sy, ox, oy);
	}

	@Override
	public void mouseMoved(final MouseEvent e) {

		super.mouseMoved(e);
		if (isEventsDisabled() || !tracerPlugin.isUIready()) return;

		last_x_in_pane_precise = myOffScreenXD(e.getX());
		last_y_in_pane_precise = myOffScreenYD(e.getY());

		boolean shift_key_down = e.isShiftDown();
		final boolean joiner_modifier_down = (tracerPlugin.requireShiftToFork) ? e.isShiftDown() && e.isAltDown() : e.isAltDown();

		if (!editMode && tracerPlugin.snapCursor &&
			plane == MultiDThreePanes.XY_PLANE && !joiner_modifier_down &&
			!shift_key_down && tracerPlugin.accessToValidImageData())
		{
			final double[] p = new double[3];
			tracerPlugin.findSnappingPointInXView(last_x_in_pane_precise,
				last_y_in_pane_precise, p);
			last_x_in_pane_precise = p[0];
			last_y_in_pane_precise = p[1];
			// Always sync panes if Z-snapping is enabled
			shift_key_down = tracerPlugin.cursorSnapWindowZ > 0;
		}

		tracerPlugin.mouseMovedTo(last_x_in_pane_precise, last_y_in_pane_precise,
			plane, shift_key_down, joiner_modifier_down);
		if (editMode) {
			setCursor((tracerPlugin.getEditingNode() == -1) ? defaultCursor
				: handCursor);
		}
		else {
			setCursor(crosshairCursor);
		}

	}

	@Override
	public void mouseEntered(final MouseEvent e) {

		if (super.isEventsDisabled() || !tracerPlugin.isUIready()) {
			super.mouseEntered(e);
			return;
		}
		if (tracerPlugin.autoCanvasActivation) imp.getWindow().toFront();
	}

	/* See ImageCanvas#handlePopupMenu(me); */
	private boolean isPopupTrigger(final MouseEvent me) {
		return (me.isPopupTrigger() || (!PlatformUtils.isMac() && (me
			.getModifiers() & Event.META_MASK) != 0));
	}

	@Override
	public void mousePressed(final MouseEvent me) {
		if (isPopupTrigger(me)) {
			showPopupMenu(me.getX(), me.getY());
			me.consume();
			return;
		}
		if (tracerPlugin.panMode || isEventsDisabled() || !tracerPlugin
			.isUIready())
		{
			super.mousePressed(me);
		}
	}

	@Override
	public void mouseReleased(final MouseEvent me) {
		if (tracerPlugin.panMode || isEventsDisabled()) {
			super.mouseReleased(me);
		}
		if (waitingForRoiDrawing && getImage().getRoi() != null && getImage().getRoi().getState() != Roi.CONSTRUCTING) { // ROI has been completed
			selectPathsByRoi();
			me.consume();
		}
	}

	private boolean selectPathsByRoi() {
		final Collection<Path> paths = pathAndFillManager.getPathsInROI((getImage().getRoi()));
		if (!paths.isEmpty()) {
			if (tracerPlugin.getUI() != null)
				tracerPlugin.getUI().getPathManager().setSelectedPaths(paths, this);
			else
				tracerPlugin.setSelectedPaths(paths, this);
		}
		getImage().deleteRoi();
		waitingForRoiDrawing = false;
		if (paths.isEmpty())
			getGuiUtils().tempMsg("No paths selected. Invalid selection ROI?");
		return !paths.isEmpty();
	}

	private boolean unsuitableToolForRoiSelection() {
		return Toolbar.getToolId() > 3;
	}

	@Override
	public void mouseClicked(final MouseEvent e) {

		if (pMenu.isShowing() || tracerPlugin.panMode || isEventsDisabled() ||
			isPopupTrigger(e))
		{
			super.mouseClicked(e);
			return;
		}

		switch (tracerPlugin.getUI().getState()) {

			case SNTUI.LOADING:
			case SNTUI.SAVING:
			case SNTUI.TRACING_PAUSED:
				return; // Do nothing
			case SNTUI.EDITING:
				impossibleEdit(true);
				break;
			case SNTUI.WAITING_FOR_SIGMA_POINT_I:
				startSigmaWizard(e.getX(), e.getY());
				break;
			case SNTUI.WAITING_FOR_SIGMA_CHOICE:
				getGuiUtils().tempMsg(
					"You must close the sigma palette to continue");
				break;

			default:
				final boolean join = e.isAltDown();
				if (tracerPlugin.snapCursor && !join && !e.isShiftDown()) {
					tracerPlugin.clickForTrace(last_x_in_pane_precise,
						last_y_in_pane_precise, plane, false);
				}
				else {
					tracerPlugin.clickForTrace(myOffScreenXD(e.getX()), myOffScreenYD(e
						.getY()), plane, join);
				}
				break;
		}

	}

	private void startSigmaWizard(final int canvasX, final int canvasY) {
		tracerPlugin.getUI().launchSigmaPaletteAround(myOffScreenX(canvasX), myOffScreenY(canvasY));
		restoreDefaultCursor();
	}

	private boolean impossibleEdit(final boolean displayError) {
		boolean invalid = !tracerPlugin.getPathAndFillManager().isSelected(tracerPlugin
			.getEditingPath());
		if (invalid && displayError) getGuiUtils().tempMsg(
			"Editing path not selected");
		if (!invalid) {
			invalid = (tracerPlugin.getEditingNode() == -1);
			if (invalid && displayError) getGuiUtils().tempMsg("No node selected");
		}
		return invalid;
	}

	private void redrawEditingPath(final String msg) {
		redrawEditingPath(getGraphics2D(getGraphics()));
		repaint();
		if (msg != null) tempMsg(msg);
	}

	private void tempMsg(final String msg) {
		SwingUtilities.invokeLater(() -> InteractiveTracerCanvas.this.getGuiUtils().tempMsg(msg));
	}

	private void redrawEditingPath(final Graphics2D g) {
		tracerPlugin.getEditingPath().drawPathAsPoints(g, this, tracerPlugin);
	}

	@Override
	protected void drawOverlay(final Graphics2D g) {
		if (!tracerPlugin.getPathAndFillManager().enableUIupdates) return;

		final boolean drawDiametersXY = tracerPlugin.getDrawDiametersXY();
		final int sliceZeroIndexed = imp.getZ() - 1;
		int eitherSideParameter = eitherSide;
		if (!just_near_slices) eitherSideParameter = -1;

		super.drawOverlay(g); // draw all paths, crosshair, etc.

		if (editMode && tracerPlugin.getEditingPath() != null) {
			redrawEditingPath(g);
			return; // no need to proceed: only editing path has been updated
		}

		// Now render temporary/incomplete paths
		final double spotDiameter = 2 * nodeDiameter();

		if (unconfirmedSegment != null) {
			unconfirmedSegment.drawPathAsPoints(this, g, getUnconfirmedPathColor(),
				plane, drawDiametersXY, sliceZeroIndexed, eitherSideParameter);
		}


		final Path currentPathFromTracer = tracerPlugin.getCurrentPath();

		if (currentPathFromTracer != null) {
			currentPathFromTracer.drawPathAsPoints(this, g, getTemporaryPathColor(),
					plane, drawDiametersXY, sliceZeroIndexed, eitherSideParameter);

			if (lastPathUnfinished && currentPath.size() == 0) { // first point in path
				final PointInImage p = new PointInImage(
						tracerPlugin.last_start_point_x * tracerPlugin.x_spacing,
						tracerPlugin.last_start_point_y * tracerPlugin.y_spacing,
						tracerPlugin.last_start_point_z * tracerPlugin.z_spacing);
				p.onPath = currentPath;
				final PathNode pn = new PathNode(p, this);
				pn.setSize(spotDiameter);
				pn.draw(g, getUnconfirmedPathColor());
			}
		}


	}

	private void enableEditMode(final boolean enable) {
		if (enable && !tracerPlugin.editModeAllowed(true)) return;
		tracerPlugin.enableEditMode(enable);
	}

	public void setTemporaryPathColor(final Color color) {
		this.temporaryColor = color;
	}

	public void setUnconfirmedPathColor(final Color color) {
		this.unconfirmedColor = color;
	}

	public void setFillColor(final Color color) {
		this.fillColor = color;
	}

	public Color getTemporaryPathColor() {
		return (temporaryColor == null) ? Color.RED : temporaryColor;
	}

	public Color getUnconfirmedPathColor() {
		return (unconfirmedColor == null) ? Color.CYAN : unconfirmedColor;
	}

	public Color getFillColor() {
		if (fillColor == null) fillColor = new Color(0, 128, 0);
		if (fillTransparent) fillColor = SNTColor.alphaColor(fillColor, 50);
		return fillColor;
	}

	protected void toggleEditMode() {
		toggleEditModeMenuItem.doClick();
	}

	protected  void togglePauseTracing() {
		togglePauseTracingMenuItem.doClick();
	}

	private void showHelpOnCountingSpines() {
		final String HELP_MSG = "<html>" //
				+ "<b>Counting Spines/Varicosities:</b>" //
				+ "<ul>" //
				+ "<li> Click over the features to be counted. Point placement may not need to be accurate, " //
				+ "but w/ 3D images it should reflect the features Z-plane</li>" //
				+ "<li> Once you have performed the count, select the Path(s) associated with the features " //
				+ "(or select none, if all Paths are to be considered) and run Path Manager's " //
				+ "<i>Analyze&rarr; Spine/Varicosity Utilities&rarr; Extract Counts from Multi-point ROIs...</i></li>" //
				+ "<li> Note that SNT only keeps a tally of the features, so you may want to save the multi-point ROis " //
				+ "in the ROI Manager during extraction</li>" //
				+ "</ul>" //
				+ "<br>" //
				+ "<b>Multi-point Tool Usage:</b>" //
				+ "<ul>" //
				+ "<li> Click on a point and drag to move it</li>" //
				+ "<li> Alt-click on a point to delete it</li>" //
				+ "<li> To delete multiple points, create an area selection while holding down the Alt key</li>" //
				+ "<li> Use <i>Edit&rarr; Selection&rarr; Select None</i> to delete a multi-point selection</li>" //
				+ "<li> Use <i>Edit&rarr; Selection&rarr; Restore Selection</i> to restore a deleted multi-point selection</li>" //
				+ "<li> Double-click on the Multi-point tool for further options and help</li>" //
				+ "</ul>" //
				+ " <br>";
		getGuiUtils().showHTMLDialog(HELP_MSG, "Counting Spines/ Varicosities", false);
	}

	/**
	 * This class implements ActionListeners for
	 * InteractiveTracerCanvas's contextual menu.
	 */
	private class AListener implements ActionListener, ItemListener {

		/* Listed shortcuts are specified in QueueJumpingKeyListener */
		public static final String CLICK_AT_MAX = "Click on Brightest Voxel Above/Below Cursor";
		public static final String FORK_NEAREST = "Fork at Nearest Node";

		public static final String SELECT_NEAREST = "Select Nearest Path";
		public static final String APPEND_NEAREST = "Add Nearest Path to Selection";
		public static final String EXTEND_SELECTED = "Continue Extending Path";
		public static final String PAUSE_SNT_TOGGLE = "Pause SNT";
		public static final String PAUSE_TRACING_TOGGLE = "Pause Tracing";
		public static final String EDIT_TOGGLE_FORMATTER = "Edit %s";

		private final static String NODE_RESET = "  Reset Active Node";
		private final static String NODE_DELETE = "  Delete Active Node";
		private final static String NODE_INSERT = "  Insert New Node at Cursor Position";
		private final static String NODE_LOCK = "  Lock Active Node";
		private final static String NODE_MOVE = "  Move Active Node to Cursor Position";
		private final static String NODE_MOVE_Z = "  Bring Active Node to Current Z-plane";
		private final static String NODE_SET_ROOT = "  Set Active Node as Tree Root...";
		private final static String NODE_SPLIT = "  Split Tree at Active Node";
		private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX = "  Connect to ";
		private final static String NODE_CONNECT_TO_PREV_EDITING_PATH_PLACEHOLDER = NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX
				+ "Unselected Crosshair Node";
		private final static String START_SHOLL = "Sholl Analysis at Nearest Node";

		@Override
		public void itemStateChanged(final ItemEvent e) {
			if (e.getSource().equals(toggleEditModeMenuItem)) {
				enableEditMode(toggleEditModeMenuItem.getState());
			}
			else if (e.getSource().equals(togglePauseSNTMenuItem)) {
				tracerPlugin.pause(togglePauseSNTMenuItem.isSelected(), false);
			}
			else if (e.getSource().equals(togglePauseTracingMenuItem)) {
				tracerPlugin.pauseTracing(togglePauseTracingMenuItem.isSelected(), true);
			}
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			if (e.getSource() == extendPathMenuItem) {

				if (tracerPlugin.tracingHalted) {
					getGuiUtils().tempMsg(
						"Tracing functions currently disabled");
					return;
				}
				else if (pathAndFillManager.size() == 0) {
					getGuiUtils().tempMsg(
						"There are no finished paths to extend");
					return;
				}
				else if (!uiReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
					getGuiUtils().tempMsg(
						"Please finish current operation before extending path");
					return;
				}
				final Path activePath = tracerPlugin.getSingleSelectedPath();
				if (activePath == null) {
					getGuiUtils().tempMsg(
						"No path selected. Please select a single path to be extended");
					return;
				}
				tracerPlugin.replaceCurrentPath(activePath);
				return;

			}

			else if (e.getActionCommand().equals(CLICK_AT_MAX)) {
				clickAtMaxPoint(false);
				return;
			}
			else if (e.getActionCommand().startsWith(FORK_NEAREST)) {

				if (!uiReadyForModeChange(SNTUI.WAITING_TO_START_PATH)) {
					getGuiUtils().tempMsg(
						"Please finish current operation before creating branch");
					return;
				}
				else if (pathAndFillManager.size() == 0) {
					getGuiUtils().tempMsg(
						"There are no finished paths to branch out from");
					return;
				}
				selectNearestPathToMousePointer(false);
				tracerPlugin.mouseMovedTo(last_x_in_pane_precise,
					last_y_in_pane_precise, plane, true, true);
				tracerPlugin.clickForTrace(last_x_in_pane_precise,
					last_y_in_pane_precise, plane, true);
				return;

			}
			else if (e.getActionCommand().equals(SELECT_NEAREST)) {
				final boolean add = ((e.getModifiers() & ActionEvent.SHIFT_MASK) > 0);
				selectNearestPathToMousePointer(add);
				return;
			}
			else if (e.getActionCommand().equals(APPEND_NEAREST)) {
				selectNearestPathToMousePointer(true);
				return;
			}
			else if (e.getActionCommand().equals(START_SHOLL)) {
				if (pathAndFillManager.size() == 0) {
					getGuiUtils().error("There are no traced paths.");
					return;
				}
				startShollAnalysis();
				return;
			}
			else if (impossibleEdit(true)) return;

			// EDIT Commands below
			if (e.getActionCommand().startsWith(NODE_CONNECT_TO_PREV_EDITING_PATH_PREFIX)) {
				connectEditingPathToPreviousEditingPath();
				return;
			}
			switch (e.getActionCommand()) {
				case NODE_RESET:
					tracerPlugin.getEditingPath().setEditableNode(-1);
					break;
				case NODE_DELETE:
					deleteEditingNode(true);
					break;
				case NODE_INSERT:
					appendLastCanvasPositionToEditingNode(true);
					break;
				case NODE_LOCK:
					toggleEditingNode(true);
					break;
				case NODE_MOVE:
					moveEditingNodeToLastCanvasPosition(true);
					break;
				case NODE_MOVE_Z:
					assignLastCanvasZPositionToEditNode(true);
					break;
				case NODE_SET_ROOT:
					assignTreeRootToEditingNode(true);
					break;
				case NODE_SPLIT:
					splitTreeAtEditingNode(true);
					break;
				default:
					SNTUtils.error("Unexpectedly got an event from an unknown source: " + e);
			}

		}
	}

	private GuiUtils getGuiUtils() {
		if (guiUtils == null)
			guiUtils = new GuiUtils(getParent());
		return guiUtils;
	}

	protected boolean isEditMode() {
		return editMode;
	}

	protected void setEditMode(final boolean editMode) {
		this.editMode = editMode;
	}

	protected void toggleEditingNode(final boolean warnOnFailure) {
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		if (editingPath.getEditableNodeIndex() < 0) {
			tempMsg("No editable node detected!");
		} else {
			editingPath.setEditableNodeLocked(!editingPath.isEditableNodeLocked());
			redrawEditingPath("Lock toggled on active node");
		}
	}

	protected void deleteEditingNode(final boolean warnOnFailure) {
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		final PointInImage editingNode = editingPath.getNode(editingPath.getEditableNodeIndex());
		if (editingPath.size() > 2) {
			if (editingPath.getJunctionNodes().stream().anyMatch(n -> n.equals(editingNode))) {
				tempMsg("Cannot delete junction node. Try to split instead.");
				return;
			}
			try {
				editingPath.removeNode(editingPath.getEditableNodeIndex());
				redrawEditingPath("Node deleted");
			}
			catch (final IllegalArgumentException exc) {
				tempMsg("Node deletion failed!");
			}
		}
		else if (new GuiUtils(this.getParent()).getConfirmation("Delete " +
			editingPath + "?", "Delete Path?"))
		{
			boolean rebuild = false;
			for (final Path p : editingPath.somehowJoins) {
				if (p.getStartJoins() == editingPath) {
					rebuild = true;
					break;
				}
			}
			editingPath.disconnectFromAll(); // Fixes ghost connection at canvas origin after deleting last node
											 // in a forked path
			tracerPlugin.getPathAndFillManager().deletePath(editingPath);
			if (rebuild) tracerPlugin.getPathAndFillManager().rebuildRelationships();
			//tracerPlugin.detectEditingPath();
			enableEditMode(false);
			tracerPlugin.updateAllViewers();
		}
	}

	protected void appendLastCanvasPositionToEditingNode(
		final boolean warnOnFailure)
	{
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		final int editingNode = editingPath.getEditableNodeIndex();
		final double[] p = new double[3];
		tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
			last_y_in_pane_precise, plane, p);
		final PointInCanvas offset = editingPath.getCanvasOffset();
		try {
			editingPath.insertNode(editingNode, new PointInImage((p[0] - offset.x) *
				tracerPlugin.x_spacing, (p[1] - offset.y) * tracerPlugin.y_spacing,
				(p[2] - offset.z) * tracerPlugin.z_spacing));
			editingPath.setEditableNode(editingNode + 1);
			redrawEditingPath("New node inserted (N=" + editingNode + ")");
		}
		catch (final IllegalArgumentException exc) {
			tempMsg("Node insertion failed!");
		}
	}

	protected void moveEditingNodeToLastCanvasPosition(
		final boolean warnOnFailure)
	{
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		final int editingNode = editingPath.getEditableNodeIndex();
		final double[] p = new double[3];
		tracerPlugin.findPointInStackPrecise(last_x_in_pane_precise,
			last_y_in_pane_precise, plane, p);
		final PointInCanvas offset = editingPath.getCanvasOffset();
		try {
			editingPath.moveNode(editingNode, new PointInImage((p[0] - offset.x) *
				editingPath.x_spacing, (p[1] - offset.y) * editingPath.y_spacing,
				(p[2] - offset.z) * editingPath.z_spacing));
			redrawEditingPath("Node moved");
		}
		catch (final IllegalArgumentException exc) {
			tempMsg("Node displacement failed!");
		}
	}

	protected void assignLastCanvasZPositionToEditNode(
		final boolean warnOnFailure)
	{
		if (impossibleEdit(warnOnFailure)) return;
		final Path editingPath = tracerPlugin.getEditingPath();
		final int editingNode = editingPath.getEditableNodeIndex();
		final PointInCanvas offset = editingPath.getCanvasOffset();
		double newZ;
		switch (plane) {
			case MultiDThreePanes.XY_PLANE:
				newZ = (imp.getZ() - 1 - offset.z) * tracerPlugin.z_spacing;
				break;
			case MultiDThreePanes.XZ_PLANE:
				newZ = (last_y_in_pane_precise - offset.y) * tracerPlugin.y_spacing;
				break;
			case MultiDThreePanes.ZY_PLANE:
				newZ = (last_x_in_pane_precise - offset.x) * tracerPlugin.x_spacing;
				break;
			default:
				newZ = editingPath.precise_z_positions[editingNode];
				break;
		}
		try {
			editingPath.moveNode(editingNode, new PointInImage(
				editingPath.precise_x_positions[editingNode],
				editingPath.precise_y_positions[editingNode], newZ));
			redrawEditingPath("Node " + editingNode + "moved to Z=" + SNTUtils
				.formatDouble(newZ, 3));
		}
		catch (final IllegalArgumentException exc) {
			tempMsg("Adjustment of Z-position failed!");
		}
	}

	protected void assignTreeRootToEditingNode(
			final boolean warnOnFailure)
	{
		if (impossibleEdit(warnOnFailure)) return;
		long start = System.currentTimeMillis();
		final Path editingPath = tracerPlugin.getEditingPath();
		final PointInImage editingNode = editingPath.getNode(editingPath.getEditableNodeIndex());
		final int treeID = editingPath.getTreeID();
		final Tree editingTree = getTreeFromID(treeID);
		final DirectedWeightedGraph editingGraph = new DirectedWeightedGraph(editingTree, false);
		editingGraph.setRoot(getMatchingPointInGraph(editingNode, editingGraph));
		final Tree newTree = editingGraph.getTreeWithSamePathStructure();
		enableEditMode(false);
		final Calibration cal = tracerPlugin.getImagePlus().getCalibration(); // snt the instance of the plugin
		newTree.list().forEach(p -> p.setSpacing(cal));
		final boolean existingEnableUiUpdates = pathAndFillManager.enableUIupdates;
		pathAndFillManager.enableUIupdates = false;
		pathAndFillManager.deletePaths(editingTree.list());
		newTree.list().forEach(p -> pathAndFillManager.addPath(p, false, true));
		SNTUtils.log("Finished re-root in " + (System.currentTimeMillis() - start) + "ms");
		pathAndFillManager.enableUIupdates = existingEnableUiUpdates;
	}

	protected void splitTreeAtEditingNode(final boolean warnOnFailure) {
		if (impossibleEdit(warnOnFailure)) return;
		long start = System.currentTimeMillis();
		final Path editingPath = tracerPlugin.getEditingPath();
		final Tree editingTree = getTreeFromID(editingPath.getTreeID());
		final PointInImage editingPoint = editingPath.getNode(editingPath.getEditableNodeIndex());
		if (editingTree.getRoot().equals(editingPoint)) {
			getGuiUtils().tempMsg("Cannot split Tree at root node.");
			return;
		}
		final DirectedWeightedGraph editingGraph = new DirectedWeightedGraph(editingTree, false);
		final SWCPoint editingVertex = getMatchingPointInGraph(editingPoint, editingGraph);
		// incomingEdgesOf should never return an empty Set if we've arrived here
		editingGraph.removeEdge(editingGraph.incomingEdgesOf(editingVertex).iterator().next());
		final DepthFirstIterator<SWCPoint, SWCWeightedEdge> depthFirstIterator = editingGraph.getDepthFirstIterator(
				editingVertex);
		final Set<SWCPoint> descendantVertexSet = new HashSet<>();
		while (depthFirstIterator.hasNext()) {
			descendantVertexSet.add(depthFirstIterator.next());
		}
		final DirectedWeightedSubgraph descendantSubgraph = editingGraph.getSubgraph(descendantVertexSet);
		final DirectedWeightedGraph descendantGraph = new DirectedWeightedGraph();
		Graphs.addGraph(descendantGraph, descendantSubgraph);
		// This also removes all related edges
		editingGraph.removeAllVertices(descendantVertexSet);
		final Tree ancestorTree = editingGraph.getTreeWithSamePathStructure();
		final Tree descendentTree = descendantGraph.getTreeWithSamePathStructure();
		enableEditMode(false);
		final Calibration cal = tracerPlugin.getImagePlus().getCalibration(); // snt the instance of the plugin
		ancestorTree.list().forEach(p -> p.setSpacing(cal));
		descendentTree.list().forEach(p -> p.setSpacing(cal));
		final boolean existingEnableUiUpdates = pathAndFillManager.enableUIupdates;
		pathAndFillManager.enableUIupdates = false;
		pathAndFillManager.deletePaths(editingTree.list());
		ancestorTree.list().forEach(p -> pathAndFillManager.addPath(p, false, true));
		descendentTree.list().forEach(p -> pathAndFillManager.addPath(p, false, true));
		SNTUtils.log("Finished split in " + (System.currentTimeMillis() - start) + "ms");
		pathAndFillManager.enableUIupdates = existingEnableUiUpdates;
	}

	private Tree getTreeFromID(final int treeID) {
		final Tree tree = new Tree();
		for (final Path p : pathAndFillManager.getPaths()) {
			if (treeID == p.getTreeID()) {
				tree.add(p);
			}
		}
		return tree;
	}

	private SWCPoint getMatchingPointInGraph(final PointInImage point, final DirectedWeightedGraph graph) {
		for (final SWCPoint p : graph.vertexSet()) {
			if (p.isSameLocation(point) && p.getPath().equals(point.getPath())) {
				return p;
			}
		}
		return null;
	}

	public void setLookAndFeel(final String lookAndFeelName) {
		GuiUtils.setLookAndFeel(lookAndFeelName, false, pMenu);
	}
}
