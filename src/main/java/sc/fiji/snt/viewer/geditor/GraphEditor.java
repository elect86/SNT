package sc.fiji.snt.viewer.geditor;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.text.NumberFormat;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.PaintScaleLegend;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.RectangleInsets;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxCompactTreeLayout;
import com.mxgraph.layout.mxEdgeLabelLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.layout.mxOrganicLayout;
import com.mxgraph.layout.mxParallelEdgeLayout;
import com.mxgraph.layout.mxPartitionLayout;
import com.mxgraph.layout.mxStackLayout;
import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.swing.mxGraphOutline;
import com.mxgraph.swing.handler.mxKeyboardHandler;
import com.mxgraph.swing.handler.mxRubberband;
import com.mxgraph.swing.util.mxMorphing;
import com.mxgraph.util.mxConstants;
import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxEventObject;
import com.mxgraph.util.mxRectangle;
import com.mxgraph.util.mxResources;
import com.mxgraph.util.mxUndoManager;
import com.mxgraph.util.mxUndoableEdit;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import com.mxgraph.util.mxUndoableEdit.mxUndoableChange;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;

import net.imagej.lut.LUTService;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.analysis.graph.AnnotationGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.viewer.Viewer2D;
import sc.fiji.snt.viewer.geditor.EditorActions.ChangeGraphAction;

public class GraphEditor extends JPanel
{

	private static final long serialVersionUID = -6561623072112577140L;

	static {
		try {
			mxResources.add("geditor/editor"); // load editor.properties
		} catch (Exception e) {
			e.printStackTrace(); // ignore
		}
	}

	protected mxGraphComponent graphComponent;
	protected mxGraphOutline graphOutline;
	protected JTabbedPane libraryPane;
	protected mxUndoManager undoManager;
	protected String appTitle;
	protected JLabel statusBar;
	protected File currentFile;
	protected mxRubberband rubberband;
	protected mxKeyboardHandler keyboardHandler;
	private EditorConsole editorConsole;
	private JPanel legendPanel;
	private JSplitPane mainPanel;
	private JSplitPane bottomPanel;
	protected EditorToolBar toolbar;
	protected boolean animateLayoutChange = true;
	private JComboBox<String> annotationMetricJCombo;

	/**
	 * Flag indicating whether the current graph has been modified 
	 */
	protected boolean modified = false;


	public GraphEditor(String appTitle, mxGraphComponent component)
	{
		// Stores and updates the frame title
		this.appTitle = appTitle;

		// Stores a reference to the graph and creates the command history
		graphComponent = component;
		final mxGraph graph = graphComponent.getGraph();
		undoManager = createUndoManager();

		adjustZoomToGuiScale();

		// Updates the modified flag if the graph model changes
		graph.getModel().addListener(mxEvent.CHANGE, changeTracker);

		// Adds the command history to the model and view
		graph.getModel().addListener(mxEvent.UNDO, undoHandler);
		graph.getView().addListener(mxEvent.UNDO, undoHandler);

		// Keeps the selection in sync with the command history
		mxIEventListener undoHandler = new mxIEventListener()
		{
			public void invoke(Object source, mxEventObject evt)
			{
				List<mxUndoableChange> changes = ((mxUndoableEdit) evt
						.getProperty("edit")).getChanges();
				graph.setSelectionCells(graph
						.getSelectionCellsForChanges(changes));
			}
		};

		undoManager.addListener(mxEvent.UNDO, undoHandler);
		undoManager.addListener(mxEvent.REDO, undoHandler);

		// Creates the graph outline component
		graphOutline = new mxGraphOutline(graphComponent);

		// Creates the library pane that contains the tabs with the palettes
		libraryPane = new JTabbedPane();
		editorConsole = new EditorConsole();
		insertConsole(getConsole());
		insertGraphCriteriaPanel();
		initColorLegend();

		// Creates the split pane that contains the tabbed pane with
		// console on the right and the graph outline on left
		bottomPanel = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
				graphOutline, libraryPane);
		bottomPanel.setResizeWeight(0.20);
		bottomPanel.setDividerSize(6);
		bottomPanel.setBorder(null);

		// Creates the split pane that contains the bottom split
		// pane and the graph component on the top of the window
		mainPanel = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
				graphComponent, bottomPanel);
		mainPanel.setOneTouchExpandable(true);
		mainPanel.setResizeWeight(0.8);
		mainPanel.setDividerSize(6);
		mainPanel.setBorder(null);

		// Create the toolbar and status bar
		statusBar = createStatusBar();
		toolbar = createToolBar();

		// Puts everything together
		setLayout(new BorderLayout());
		add(toolbar, BorderLayout.NORTH);
		add(mainPanel, BorderLayout.CENTER);
		add(statusBar, BorderLayout.SOUTH);

		// Display some useful information about repaint events
		installRepaintListener();

		// Installs rubberband selection and handling for some special
		// keystrokes such as F2, Control-C, -V, X, A etc.
		installHandlers();
		installListeners();
		updateTitle();
		if (graphComponent instanceof SNTGraphComponent) {
			// update UI
			((SNTGraphComponent)graphComponent).assignEditor(this);
		}

	}

	private int getDefaultFontSizeInGUI() {
		return new JLabel().getFont().getSize();
	}

	private void adjustZoomToGuiScale() {
		try {
			final mxStylesheet styleSheet = graphComponent.getGraph().getStylesheet();
			final int currentFontSize = (int) styleSheet.getDefaultVertexStyle()
					.getOrDefault(mxConstants.DEFAULT_FONTSIZE, mxConstants.DEFAULT_FONTSIZE);
			final int uiFontSize = getDefaultFontSizeInGUI();
			if (currentFontSize < uiFontSize)
				graphComponent.zoomTo(Math.round(uiFontSize / currentFontSize), true);

		} catch (final ClassCastException ignored) {
			System.out.println("Failed to adjust zoom levels");
		}
	}

	protected mxIEventListener undoHandler = new mxIEventListener()
	{
		public void invoke(Object source, mxEventObject evt)
		{
			undoManager.undoableEditHappened((mxUndoableEdit) evt
					.getProperty("edit"));
		}
	};

	protected mxIEventListener changeTracker = new mxIEventListener()
	{
		public void invoke(Object source, mxEventObject evt)
		{
			setModified(true);
		}
	};
	private JFormattedTextField annotationThresholdField;
	private JFormattedTextField annotationDepthField;

	protected mxUndoManager createUndoManager()
	{
		return new mxUndoManager();
	}

	protected void installHandlers()
	{
		rubberband = new mxRubberband(graphComponent);
		keyboardHandler = new EditorKeyboardHandler(graphComponent);
	}

	protected EditorToolBar createToolBar()
	{
		return new EditorToolBar(this, JToolBar.HORIZONTAL);
	}

	/**
	 * 
	 */
	protected JLabel createStatusBar()
	{
		JLabel statusBar = new JLabel(mxResources.get("ready"));
		statusBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

		return statusBar;
	}

	/**
	 * 
	 */
	protected void installRepaintListener()
	{
		graphComponent.getGraph().addListener(mxEvent.REPAINT,
				new mxIEventListener()
				{
					public void invoke(Object source, mxEventObject evt)
					{
						String buffer = (graphComponent.getTripleBuffer() != null) ? ""
								: " (unbuffered)";
						mxRectangle dirty = (mxRectangle) evt
								.getProperty("region");

						if (dirty == null)
						{
							status("View updated" + buffer);
						}
						else
						{
							status("Updated: x=" + (int) (dirty.getX()) + " y="
									+ (int) (dirty.getY()) + " w="
									+ (int) (dirty.getWidth()) + " h="
									+ (int) (dirty.getHeight()) + buffer);
						}
					}
				});
	}

	private void insertConsole(final EditorConsole editorConsole) {
		final String regTitle = "<html>Console ";
		final String higTitle = "<html><b>Console</b>";
		int editorConsolePosition = libraryPane.getTabCount();
		libraryPane.add(regTitle, editorConsole);

		// Highlight tab title if console contents changed and it does not have focus
		editorConsole.getTextArea().getDocument().addDocumentListener(new DocumentListener() {

			@Override
			public void removeUpdate(final DocumentEvent e) {
				// do nothing
			}

			@Override
			public void insertUpdate(final DocumentEvent e) {
				boolean highlight = editorConsolePosition != libraryPane.getSelectedIndex();
				libraryPane.setTitleAt(editorConsolePosition, (highlight) ? higTitle : regTitle);
			}

			@Override
			public void changedUpdate(final DocumentEvent arg0) {
				// do nothing
			}
		});
		// Reset tab title highlight if console tab has focus
		libraryPane.addChangeListener(e -> {
			if (editorConsolePosition == libraryPane.getSelectedIndex()) {
				libraryPane.setTitleAt(editorConsolePosition, regTitle);
			}
		});
	}

	private void initColorLegend() {
		if (legendPanel == null)  legendPanel = getNoLegendPanel();
		libraryPane.add("Legend", legendPanel);
	}

	protected void refresh() {
		if (graphComponent instanceof AnnotationGraphComponent) {
			AnnotationGraphAdapter adapter = (AnnotationGraphAdapter) ((AnnotationGraphComponent) graphComponent)
					.getGraph();
			AnnotationGraph graph = adapter.getAnnotationGraph();
			SwingUtilities.invokeLater(() -> {
				annotationMetricJCombo.setSelectedItem(graph.getMetric());
				annotationThresholdField.setValue(graph.getThreshold());
				annotationDepthField.setValue(graph.getMaxOntologyDepth());
			});
		}
		toolbar.refresh();
	}

	private void insertGraphCriteriaPanel() {

		String metricTip = "<HTML><div WIDTH=500>The morphometric criteria used to define connectivity between brain areas";
		String mintTresTip= "<HTML><div WIDTH=500>Connectiviy to a target area will only be considered if the chosen metric is above this value";
		String maxDepthTip = "<HTML><div WIDTH=500> The highest ontology level to be considered when defining brain areas.";

		JPanel panel = new JPanel(new GridBagLayout());
		libraryPane.add("Graph Criteria", panel);
		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.HORIZONTAL;
		// Row 1: header
		c.gridx = 0;
		c.gridy = 0;
		c.insets.bottom = getDefaultFontSizeInGUI() / 2;
		GuiUtils.addSeparator(panel, "Annotation Graphs:", false, c);
		c.insets.bottom = 0;
		c.insets.top = 0;

		// Row 2: labels
		c.gridy++;
		c.gridx = 0;
		JLabel label = new JLabel("Metric:");
		label.setToolTipText(metricTip);
		panel.add(label, c);
		c.gridx = 1;
		label = new JLabel("Min. Threshold:");
		label.setToolTipText(mintTresTip);
		panel.add(label, c);
		c.gridx = 2;
		label = new JLabel("Max. Ont. Depth:");
		label.setToolTipText(maxDepthTip);
		panel.add(label, c);

		// Row 3: annot. graph fields
		c.gridy++;
		c.gridx = 0;
		annotationMetricJCombo = new JComboBox<>(AnnotationGraph.getMetrics());
		annotationMetricJCombo.setToolTipText(metricTip);
		panel.add(annotationMetricJCombo, c);
		annotationThresholdField = new JFormattedTextField(NumberFormat.getNumberInstance());
		annotationThresholdField.setToolTipText(mintTresTip);
		annotationThresholdField.setValue(new Double(5));
		annotationThresholdField.setColumns(8);

		c.gridx++;
		panel.add(annotationThresholdField, c);
		annotationDepthField = new JFormattedTextField(NumberFormat.getNumberInstance());
		annotationDepthField.setToolTipText(maxDepthTip);
		annotationDepthField.setValue(new Integer(5));
		annotationDepthField.setColumns(8);
		c.gridx++;
		panel.add(annotationDepthField, c);
		JButton applyButton = new JButton("Apply");
		applyButton.addActionListener( e-> {
			ChangeGraphAction changeGraphAction = new EditorActions.ChangeGraphAction(this, 
					(String) annotationMetricJCombo.getSelectedItem(),
					((Number) annotationThresholdField.getValue()).doubleValue(),
					((Number) annotationDepthField.getValue()).intValue());
			changeGraphAction.actionPerformed(e);
			
		});
		c.gridx++;
		panel.add(applyButton, c);
//
//		// Row 4: header
//		c.gridx = 0;
//		c.gridy++;
//		GuiUtils.addSeparator(panel, "Trees:", true, c);
//
//		// Row 5: Tree graph options
//		c.gridx = 0;
//		c.gridy++;
//		JCheckBox simplify = new JCheckBox("Simplify");
//		panel.add(simplify, c);
	}

	/**
	 * 
	 */
	protected void mouseWheelMoved(MouseWheelEvent e)
	{
		if (e.getWheelRotation() < 0)
		{
			graphComponent.zoomIn();
		}
		else
		{
			graphComponent.zoomOut();
		}

		status(mxResources.get("scale") + ": "
				+ (int) (100 * graphComponent.getGraph().getView().getScale())
				+ "%");
	}

	/**
	 * 
	 */
	protected void showOutlinePopupMenu(MouseEvent e)
	{
		Point pt = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(),
				graphComponent);
		JCheckBoxMenuItem item = new JCheckBoxMenuItem(
				mxResources.get("magnifyPage"));
		item.setSelected(graphOutline.isFitPage());

		item.addActionListener(new ActionListener()
		{
			/**
			 * 
			 */
			public void actionPerformed(ActionEvent e)
			{
				graphOutline.setFitPage(!graphOutline.isFitPage());
				graphOutline.repaint();
			}
		});

		JCheckBoxMenuItem item2 = new JCheckBoxMenuItem(
				mxResources.get("showLabels"));
		item2.setSelected(graphOutline.isDrawLabels());

		item2.addActionListener(new ActionListener()
		{
			/**
			 * 
			 */
			public void actionPerformed(ActionEvent e)
			{
				graphOutline.setDrawLabels(!graphOutline.isDrawLabels());
				graphOutline.repaint();
			}
		});

		JCheckBoxMenuItem item3 = new JCheckBoxMenuItem(
				mxResources.get("buffering"));
		item3.setSelected(graphOutline.isTripleBuffered());

		item3.addActionListener(new ActionListener()
		{
			/**
			 * 
			 */
			public void actionPerformed(ActionEvent e)
			{
				graphOutline.setTripleBuffered(!graphOutline.isTripleBuffered());
				graphOutline.repaint();
			}
		});

		JPopupMenu menu = new JPopupMenu();
		menu.add(item);
		menu.add(item2);
		menu.add(item3);
		menu.show(graphComponent, pt.x, pt.y);

		e.consume();
	}

	/**
	 * 
	 */
	protected void showGraphPopupMenu(MouseEvent e)
	{
		Point pt = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(),
				graphComponent);
		EditorPopupMenu menu = new EditorPopupMenu(GraphEditor.this);
		menu.show(graphComponent, pt.x, pt.y);

		e.consume();
	}

	/**
	 * 
	 */
	protected void mouseLocationChanged(MouseEvent e)
	{
		status(e.getX() + ", " + e.getY());
	}

	/**
	 * 
	 */
	protected void installListeners()
	{
		// Installs mouse wheel listener for zooming
		MouseWheelListener wheelTracker = new MouseWheelListener()
		{
			/**
			 * 
			 */
			public void mouseWheelMoved(MouseWheelEvent e)
			{
				if (e.getSource() instanceof mxGraphOutline
						|| e.isControlDown())
				{
					GraphEditor.this.mouseWheelMoved(e);
				}
			}

		};

		// Handles mouse wheel events in the outline and graph component
		graphOutline.addMouseWheelListener(wheelTracker);
		graphComponent.addMouseWheelListener(wheelTracker);

		// Installs the popup menu in the outline
		graphOutline.addMouseListener(new MouseAdapter()
		{

			/**
			 * 
			 */
			public void mousePressed(MouseEvent e)
			{
				// Handles context menu on the Mac where the trigger is on mousepressed
				mouseReleased(e);
			}

			/**
			 * 
			 */
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showOutlinePopupMenu(e);
				}
			}

		});

		// Installs the popup menu in the graph component
		graphComponent.getGraphControl().addMouseListener(new MouseAdapter()
		{

			/**
			 * 
			 */
			public void mousePressed(MouseEvent e)
			{
				// Handles context menu on the Mac where the trigger is on mousepressed
				mouseReleased(e);
			}

			/**
			 * 
			 */
			public void mouseReleased(MouseEvent e)
			{
				if (e.isPopupTrigger())
				{
					showGraphPopupMenu(e);
				}
			}

		});

		// Installs a mouse motion listener to display the mouse location
		graphComponent.getGraphControl().addMouseMotionListener(
				new MouseMotionListener()
				{

					/*
					 * (non-Javadoc)
					 * @see java.awt.event.MouseMotionListener#mouseDragged(java.awt.event.MouseEvent)
					 */
					public void mouseDragged(MouseEvent e)
					{
						mouseLocationChanged(e);
					}

					/*
					 * (non-Javadoc)
					 * @see java.awt.event.MouseMotionListener#mouseMoved(java.awt.event.MouseEvent)
					 */
					public void mouseMoved(MouseEvent e)
					{
						mouseDragged(e);
					}

				});
	}

	/**
	 * 
	 */
	public void setCurrentFile(File file)
	{
		File oldValue = currentFile;
		currentFile = file;

		firePropertyChange("currentFile", oldValue, file);

		if (oldValue != file)
		{
			updateTitle();
		}
	}

	/**
	 * 
	 */
	public File getCurrentFile()
	{
		return currentFile;
	}

	/**
	 * 
	 * @param modified
	 */
	public void setModified(boolean modified)
	{
		boolean oldValue = this.modified;
		this.modified = modified;

		firePropertyChange("modified", oldValue, modified);

		if (oldValue != modified)
		{
			updateTitle();
		}
	}

	/**
	 * 
	 * @return whether or not the current graph has been modified
	 */
	public boolean isModified()
	{
		return modified;
	}

	/**
	 * 
	 */
	public mxGraphComponent getGraphComponent()
	{
		return graphComponent;
	}

	/**
	 * 
	 */
	public mxGraphOutline getGraphOutline()
	{
		return graphOutline;
	}
	
	/**
	 * 
	 */
	public JTabbedPane getLibraryPane()
	{
		return libraryPane;
	}

	/**
	 * 
	 */
	public mxUndoManager getUndoManager()
	{
		return undoManager;
	}

	/**
	 * 
	 * @param name
	 * @param action
	 * @return a new Action bound to the specified string name
	 */
	public Action bind(String name, final Action action)
	{
		return bind(name, action, (String)null);
	}

	/**
	 * 
	 * @param name
	 * @param action
	 * @return a new Action bound to the specified string name and icon
	 */
	@SuppressWarnings("serial")
	public Action bind(String name, final Action action, String iconUrl)
	{
		AbstractAction newAction = new AbstractAction(name, (iconUrl != null) ? new ImageIcon(
				GraphEditor.class.getResource(iconUrl)) : null)
		{
			public void actionPerformed(ActionEvent e)
			{
				action.actionPerformed(new ActionEvent(getGraphComponent(), e
						.getID(), e.getActionCommand()));
			}
		};
		
		newAction.putValue(Action.SHORT_DESCRIPTION, action.getValue(Action.SHORT_DESCRIPTION));
		
		return newAction;
	}

	@SuppressWarnings("serial")
	public Action bind(String name, final Action action, Icon icon)
	{
		AbstractAction newAction = new AbstractAction(name, icon)
		{
			public void actionPerformed(ActionEvent e)
			{
				action.actionPerformed(new ActionEvent(getGraphComponent(), e
						.getID(), e.getActionCommand()));
			}
		};
		
		newAction.putValue(Action.SHORT_DESCRIPTION, action.getValue(Action.SHORT_DESCRIPTION));
		return newAction;
	}

	/**
	 * 
	 * @param msg
	 */
	public void status(String msg)
	{
		statusBar.setText(msg);
	}

	public void status(String msg, final boolean error) {
		if (error) {
			status("<html><font color='red'>" + msg + "</font>");
		} else {
			status(msg);
		}
	}

	/**
	 * 
	 */
	public void updateTitle()
	{
		JFrame frame = (JFrame) SwingUtilities.windowForComponent(this);

		if (frame != null)
		{
			String title = (currentFile != null) ? currentFile
					.getAbsolutePath() : mxResources.get("newDiagram");

			if (modified)
			{
				title += "*";
			}

			frame.setTitle(title + " - " + appTitle);
		}
	}

	void about() {
		JFrame frame = (JFrame) SwingUtilities.windowForComponent(this);
		if (frame != null) {
			String msg = "<p><b>Graph Viewer</b></p>" //
					+ "<p>" //
					+ "Graph Viewer is SNT&#39;s graph visualization tool built around " //
					+ "<a href='https://jgrapht.org/'>JGraphT</a> and <a href='https://github.com/jgraph/jgraphx'>JGraphX</a>." //
					+ "</p><p>" //
					+ "The GUI relies heavily on a JGraphX <em>Graph Editor</em> demo written by Gaudenz Alder and others at " //
					+ "JGraph Ltd between 2001&mdash;2014, and released under the " //
					+ "<a href='https://github.com/jgraph/jgraphx/blob/master/license.txt'>BSD license</a>. " //
					+ "</p><p>" //
					+ "Special thanks to JeanYves Tinevez for writing JGraphT/JGraphX adapters and " //
					+ "Vladimir Sitnikov for making JGraphX easily available on Maven Central." //
					+ "</p>";
			GuiUtils.showHTMLDialog(msg, "About Graph Viewer");
		}
	}

	/**
	 * 
	 */
	public void exit() {
		if (getConsole() != null) getConsole().restore();
		final JFrame frame = (JFrame) SwingUtilities.windowForComponent(this);
		if (frame != null && new GuiUtils(this).getConfirmation("Exit Graph Viewer?", "Really Quit?")) {
			frame.dispose();
		}
	}

	/**
	 * 
	 */
	public void setLookAndFeel(String clazz)
	{
		JFrame frame = (JFrame) SwingUtilities.windowForComponent(this);

		if (frame != null)
		{
			try
			{
				UIManager.setLookAndFeel(clazz);
				SwingUtilities.updateComponentTreeUI(frame);

				// Needs to assign the key bindings again
				keyboardHandler = new EditorKeyboardHandler(graphComponent);
			}
			catch (Exception e1)
			{
				e1.printStackTrace();
			}
		}
	}

	public JFrame createFrame(final Context context)
	{
		
		JFrame frame = new JFrame();
		frame.getContentPane().add(this);
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				exit();
			}
		});
		frame.setJMenuBar(new EditorMenuBar(this, context));
		frame.setSize(870, 640);

		// Updates the frame title
		updateTitle();
		getConsole().redirect();

		return frame;
	}

	/**
	 * Creates an action that executes the specified layout.
	 * 
	 * @param key Key to be used for getting the label from mxResources and also
	 * to create the layout instance for the commercial graph editor example.
	 * @return an action that executes the specified layout
	 */
	@SuppressWarnings("serial")
	public Action graphLayout(final String key)
	{
		final mxIGraphLayout layout = createLayout(key);
		if (layout != null)
		{
			return new AbstractAction(mxResources.get(key))
			{
				public void actionPerformed(ActionEvent e)
				{
					final mxGraph graph = graphComponent.getGraph();
					Object cell = graph.getSelectionCell();

					if (cell == null
							|| graph.getModel().getChildCount(cell) == 0)
					{
						cell = graph.getDefaultParent();
					}

					graph.getModel().beginUpdate();
					try
					{
						long t0 = System.currentTimeMillis();
						layout.execute(cell);
						status("Layout: " + (System.currentTimeMillis() - t0)
								+ " ms");
					}
					finally
					{
						if (animateLayoutChange) {
							mxMorphing morph = new mxMorphing(graphComponent, 20, 1.2, 20);
							morph.addListener(mxEvent.DONE, (sender, evt) -> graph.getModel().endUpdate());
							morph.startAnimation();
						} else {
							graph.getModel().endUpdate();
						}
					}

				}

			};
		}
		else
		{
			return new AbstractAction(mxResources.get(key))
			{

				public void actionPerformed(ActionEvent e)
				{
					JOptionPane.showMessageDialog(graphComponent,
							mxResources.get("noLayout"));
				}

			};
		}
	}

	/**
	 * Creates a layout instance for the given identifier.
	 */
	protected mxIGraphLayout createLayout(String ident)
	{
		mxIGraphLayout layout = null;

		if (ident != null)
		{
			mxGraph graph = graphComponent.getGraph();

			if (ident.equals("verticalHierarchical"))
			{
				layout = new mxHierarchicalLayout(graph);
			}
			else if (ident.equals("horizontalHierarchical"))
			{
				layout = new mxHierarchicalLayout(graph, JLabel.WEST);
			}
			else if (ident.equals("verticalTree"))
			{
				layout = new mxCompactTreeLayout(graph, false);
			}
			else if (ident.equals("horizontalTree"))
			{
				layout = new mxCompactTreeLayout(graph, true);
			}
			else if (ident.equals("parallelEdges"))
			{
				layout = new mxParallelEdgeLayout(graph);
			}
			else if (ident.equals("placeEdgeLabels"))
			{
				layout = new mxEdgeLabelLayout(graph);
			}
			else if (ident.equals("organicLayout"))
			{
				mxOrganicLayout olayout = new mxOrganicLayout(graph);
//				olayout.setMinDistanceLimit(5);
//				olayout.setMaxIterations(2000);
//				olayout.setEdgeCrossingCostFactor(7000);
				return olayout;
			}
			if (ident.equals("verticalPartition"))
			{
				layout = new mxPartitionLayout(graph, false)
				{
					/**
					 * Overrides the empty implementation to return the size of the
					 * graph control.
					 */
					public mxRectangle getContainerSize()
					{
						return graphComponent.getLayoutAreaSize();
					}
				};
			}
			else if (ident.equals("horizontalPartition"))
			{
				layout = new mxPartitionLayout(graph, true)
				{
					/**
					 * Overrides the empty implementation to return the size of the
					 * graph control.
					 */
					public mxRectangle getContainerSize()
					{
						return graphComponent.getLayoutAreaSize();
					}
				};
			}
			else if (ident.equals("verticalStack"))
			{
				layout = new mxStackLayout(graph, false)
				{
					/**
					 * Overrides the empty implementation to return the size of the
					 * graph control.
					 */
					public mxRectangle getContainerSize()
					{
						return graphComponent.getLayoutAreaSize();
					}
				};
			}
			else if (ident.equals("horizontalStack"))
			{
				layout = new mxStackLayout(graph, true)
				{
					/**
					 * Overrides the empty implementation to return the size of the
					 * graph control.
					 */
					public mxRectangle getContainerSize()
					{
						return graphComponent.getLayoutAreaSize();
					}
				};
			}
			else if (ident.equals("circleLayout"))
			{
				layout = new mxCircleLayout(graph);
			}
		}

		return layout;
	}

	public void setBottomPaneVisible(final boolean visible) {
		graphOutline.setVisible(visible);
		libraryPane.setVisible(visible);
		graphOutline.revalidate();
		libraryPane.revalidate();
		SwingUtilities.invokeLater(() -> {
			if (graphOutline.isVisible()) {
				mainPanel.setDividerLocation(getHeight() - 350);
				bottomPanel.setDividerLocation(200);
				mainPanel.setDividerSize(6);
			} else {
				mainPanel.setDividerLocation(1d);
				mainPanel.setDividerSize(0);
			}
		});
	}

	public EditorConsole getConsole() {
		return editorConsole;
	}

	public void setLegend(final String colorTable, double min, double max) {
		setLegend(colorTable, null, min, max);
	}

	public void setLegend(final String colorTable, final String label, double min, double max) {
		final TreeColorMapper lutRetriever = new TreeColorMapper(new Context(LUTService.class));
		final ColorTable cTable = lutRetriever.getColorTable(colorTable);
		setLegend(cTable, label, min, max);
	}

	public void setLegend(final ColorTable colorTable, final String label, double min, double max) {

		/* Flavor of Viewer2D to produce an empty viewer with just ColorTable legend */
		class LegendViewer extends Viewer2D {
			private Font font;
			private String title;
			private Color color;
			LegendViewer(final ColorTable colorTable, final String metric, final double min, final double max) {
				this.colorTable = colorTable;
				this.min = min;
				this.max = max;
				font = new JLabel().getFont();
				title = metric;
				color = getForegroundColor();
			}

			PaintScaleLegend getLegend() {
				final PaintScaleLegend legend = getPaintScaleLegend(colorTable, min, max);
				legend.setPosition(RectangleEdge.TOP);
				legend.setMargin(10, 50, 0, 50);
				legend.getAxis().setLabelPaint(color);
				legend.getAxis().setAxisLinePaint(color);
				legend.getAxis().setTickLabelPaint(color);
				legend.getAxis().setTickMarkPaint(color);
				legend.getAxis().setLabelFont(font);
				legend.getAxis().setTickLabelFont(font);
				legend.getAxis().setTickMarkOutsideLength(font.getSize2D()/2);
				legend.getAxis().setTickLabelInsets(new RectangleInsets(2, 2 , font.getSize2D()/2, 2));
				return legend;
			}

			JFreeChart getDummyChart() {
				final XYPlot dummyPlot = new XYPlot();
				dummyPlot.setBackgroundPaint(null);
				dummyPlot.setOutlineVisible(false);
				final JFreeChart dummyChart = new JFreeChart(dummyPlot);
				dummyChart.setBorderVisible(false);
				dummyChart.setTitle(title);
				if (title != null) {
					dummyChart.getTitle().setPaint(color);
					dummyChart.getTitle().setFont(font);
				}
				return dummyChart;
			}

			JPanel getAssembledPanel() {
				final JFreeChart chart = getDummyChart();
				chart.addSubtitle(getLegend());
				ChartPanel panel = new ChartPanel(chart);
				JPopupMenu popup = panel.getPopupMenu();
				if (popup == null) return panel;
				tweakPopupMenu(popup);
				return panel;
			}

			private void tweakPopupMenu(final JPopupMenu popup ) {
				// Remove entries that are irrelevant for the legend
				for ( Component component : popup.getComponents()) {
					if (component instanceof JMenuItem) {
						final String cName = ((JMenuItem) component).getText();
						if (cName != null && (cName.startsWith("Zoom") || cName.startsWith("Auto Range"))) {
							popup.remove(component);
						}
					}
				}
				popup.addSeparator();
				final JMenuItem jmi = new JMenuItem("Clear");
				jmi.addActionListener(e -> setLegend((ColorTable)null, null, 0d, 0d));
				popup.add(jmi);
			}
		}

		final JPanel newContents = (colorTable == null) ? getNoLegendPanel()
				: new LegendViewer(colorTable, label, min, max).getAssembledPanel();
		legendPanel.removeAll();
		legendPanel.add(newContents);
		legendPanel.validate();
		legendPanel.repaint();
	}

	private Color getForegroundColor() { // Dark theme support
		return SNTColor.contrastColor(new JPanel().getBackground());
	}

	private JPanel getNoLegendPanel() {
		final JPanel panel = new JPanel();
		final JLabel label = new JLabel("No Color Mapping Currently Exists");
		label.setForeground(getForegroundColor());
		panel.add(label);
		return panel;
	}

	public JSplitPane getBottomPanel() {
		return bottomPanel;
	}

	class CmdRunner extends SwingWorker<Void, Void> {

		private final CommandService cmdService;
		private final Class<? extends Command> commandClass;
		private final boolean process;
		private final Object[] inputs;

		public CmdRunner(final CommandService cmdService, final Class<? extends Command> commandClass,
				final boolean process, Object... inputs) {
			this.cmdService = cmdService;
			this.commandClass = commandClass;
			this.process = process;
			this.inputs = inputs;
			status("Running command...");
		}

		@Override
		public Void doInBackground() {
			cmdService.run(commandClass, process, inputs);
			return null;
		}

		@Override
		protected void done() {
			status("Command terminated...");
		}
	}

}