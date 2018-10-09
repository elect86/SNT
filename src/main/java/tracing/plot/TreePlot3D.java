/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2018 Fiji developers.
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
package tracing.plot;

import java.awt.Component;
import java.awt.Frame;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.jzy3d.chart.AWTChart;
import org.jzy3d.chart.Chart;
import org.jzy3d.chart.controllers.ControllerType;
import org.jzy3d.chart.controllers.camera.AbstractCameraController;
import org.jzy3d.chart.controllers.mouse.AWTMouseUtilities;
import org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController;
import org.jzy3d.colors.Color;
import org.jzy3d.io.obj.OBJFileLoader;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord2d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.plot3d.primitives.AbstractDrawable;
import org.jzy3d.plot3d.primitives.LineStrip;
import org.jzy3d.plot3d.primitives.Point;
import org.jzy3d.plot3d.primitives.Shape;
import org.jzy3d.plot3d.primitives.vbo.drawable.DrawableVBO;
import org.jzy3d.plot3d.rendering.canvas.ICanvas;
import org.jzy3d.plot3d.rendering.canvas.Quality;
import org.jzy3d.plot3d.rendering.legends.colorbars.AWTColorbarLegend;
import org.jzy3d.plot3d.rendering.lights.LightSet;
import org.jzy3d.plot3d.rendering.scene.Scene;
import org.jzy3d.plot3d.rendering.view.View;
import org.jzy3d.plot3d.rendering.view.ViewportMode;
import org.jzy3d.plot3d.rendering.view.modes.CameraMode;
import org.jzy3d.plot3d.rendering.view.modes.ViewBoundMode;
import org.jzy3d.plot3d.rendering.view.modes.ViewPositionMode;

import com.jidesoft.swing.CheckBoxList;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import tracing.Path;
import tracing.SNT;
import tracing.Tree;
import tracing.analysis.TreeColorizer;
import tracing.gui.GuiUtils;
import tracing.util.PointInImage;


/**
 * Implements the SNT Reconstruction Viewer. Relies heavily on the
 * {@code org.jzy3d}.
 * 
 * @author Tiago Ferreira
 */
public class TreePlot3D {

	private final static float DEF_NODE_RADIUS = 3f;
	private final Map<String, Shape> plottedTrees;
	private Chart chart;
	private View view;
	private Frame frame;
	private AWTColorbarLegend cbar;
	private GuiUtils gUtils;
	private KeyController keyControler;
	private MouseController mouseControler;
	private float thickness = DEF_NODE_RADIUS;
	private String screenshotDir;


	/**
	 * Instantiates a new TreePlot3D.
	 */
	public TreePlot3D() {
		plottedTrees = new LinkedHashMap<>();
		initView();
		chart.black();
		setScreenshotDirectory("");
	}

	private boolean chartExists() {
		return chart != null && chart.getCanvas() != null;
	}

	/* returns true if chart was initialized */
	private boolean initView() {
		if (chartExists())
			return false;
		chart = new AWTChart(Chart.DEFAULT_QUALITY);
		view = chart.getView();
		return true;
	}

	private Color fromAWTColor(final java.awt.Color color) {
		return (color == null) ? Color.WHITE
				: new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
	}

	private String getUniqueLabel(final Tree tree) {
		String key = tree.getLabel();
		if (key == null || key.isEmpty() || plottedTrees.containsKey(key)) {
			key = "Tree " + plottedTrees.size();
		}
		return key;
	}

	/**
	 * Adds a tree to this plot. It is displayed immediately if {@link #show()} has
	 * been called.
	 *
	 * @param tree the {@link Tree)} to be added. The Tree's label will be used as
	 *             identifier. It is expected to be unique when plotting multiple
	 *             Trees, if not (or no label exists) a unique label will be
	 *             generated based on the number of Trees currently plotted.
	 * 
	 * @see {@link Tree#getLabel()}
	 * @see #remove(String)
	 */
	public void add(final Tree tree) {

		final List<LineStrip> lines = new ArrayList<>();
		for (final Path p : tree.list()) {
			final LineStrip line = new LineStrip(p.size());
			for (int i = 0; i < p.size(); ++i) {
				final PointInImage pim = p.getPointInImage(i);
				final Coord3d coord = new Coord3d(pim.x, pim.y, pim.z);
				final Color color = fromAWTColor(p.hasNodeColors() ? p.getNodeColor(i) : p.getColor());
				final float width = Math.max((float) p.getNodeRadius(i), DEF_NODE_RADIUS);
				line.add(new Point(coord, color, width));
			}
			line.setShowPoints(true);
			line.setWireframeWidth(thickness);
			lines.add(line);
		}

		// group all lines into a Composite
		final Shape surface = new Shape();
		surface.add(lines);
		surface.setFaceDisplayed(true);
		surface.setWireframeDisplayed(true);
		plottedTrees.put(getUniqueLabel(tree), surface);
		initView();
		chart.add(surface);
	}

	/**
	 * Adds a color bar legend (LUT ramp).
	 *
	 * @param colorTable the color table
	 * @param min        the minimum value in the color table
	 * @param max        the maximum value in the color table
	 */
	public void addColorBarLegend(final ColorTable colorTable, final float min, final float max) {
		final Shape surface = new Shape();
		surface.setColorMapper(new ColorTableMapper(colorTable, min, max));
		cbar = new AWTColorbarLegend(surface, view.getAxe().getLayout());
		setColorbarColors(view.getBackgroundColor() == Color.BLACK);
		// cbar.setMinimumSize(new Dimension(100, 600));
		surface.setLegend(cbar);
		chart.add(surface);
	}

	private void setColorbarColors(final boolean darkMode) {
		if (cbar == null)
			return;
		if (darkMode) {
			cbar.setBackground(Color.BLACK);
			cbar.setForeground(Color.WHITE);
		} else {
			cbar.setBackground(Color.WHITE);
			cbar.setForeground(Color.BLACK);
		}
	}

	/**
	 * Shows this TreePlot and returns a reference to its frame.
	 *
	 * @return the frame containing the plot.
	 */
	public Frame show() {
		if (!initView() && frame != null) {
			chart.render();
			frame.setVisible(true);
			return frame;
		}
		plottedTrees.forEach((k, surface) -> {
			chart.add(surface, false);
		});
		chart.setAxeDisplayed(false);
		view.getCamera().setViewportMode(ViewportMode.STRETCH_TO_FILL);
		keyControler = new KeyController(chart);
		mouseControler = new MouseController(chart);
		chart.getCanvas().addKeyController(keyControler);
		chart.getCanvas().addMouseController(mouseControler);
		frame = (Frame) chart.open("SNT Reconstruction Viewer", 800, 600);
		gUtils = new GuiUtils((Component) chart.getCanvas());
		displayMsg("Press 'H' for help", 3000);
		return frame;
	}

	private void displayMsg(final String msg) {
		displayMsg(msg, 2500);
	}

	private void displayMsg(final String msg, final int msecs) {
		if (gUtils != null && chartExists()) {
			gUtils.setTmpMsgTimeOut(msecs);
			gUtils.tempMsg(msg);
		} else {
			System.out.println(msg);
		}
	}

	/**
	 * Returns the Collection of trees in this plot.
	 *
	 * @return the plotted Trees (keys being the Tree identifier as per
	 *         {@link #add(Tree)})
	 */
	public Map<String, Shape> getTrees() {
		return plottedTrees;
	}

	/**
	 * Removes the specified Tree.
	 *
	 * @param treeLabel the key defining the tree to be removed.
	 * @return true, if tree was successfully removed.
	 * @see #add(Tree)
	 */
	public boolean remove(final String treeLabel) {
		final Shape tree = plottedTrees.get(treeLabel);
		if (tree == null)
			return false;
		boolean removed = plottedTrees.remove(treeLabel) != null;
		if (chart != null) {
			removed = removed && chart.getScene().getGraph().remove(tree);
		}
		return removed;
	}

	/**
	 * Toggles the visibility of a plotted Tree.
	 *
	 * @param treeLabel the identifier of the tree as per {@link #add(Tree)}
	 * @param visible   whether the Tree should be displayed
	 */
	public void setVisible(final String treeLabel, final boolean visible) {
		final Shape tree = plottedTrees.get(treeLabel);
		if (tree != null)
			tree.setDisplayed(visible);
	}

	/**
	 * Sets the screenshot directory.
	 *
	 * @param screenshotDir the absolute file path of the screenshot saving
	 *                      directory. Set it to {@code null} to have screenshots
	 *                      saved in the default directory: the Desktop folder of
	 *                      the user's home directory
	 */
	public void setScreenshotDirectory(final String screenshotDir) {
		if (screenshotDir == null || screenshotDir.isEmpty()) {
			this.screenshotDir = System.getProperty("user.home") + File.separator + "Desktop";
		} else {
			this.screenshotDir = screenshotDir;
		}
	}

	/**
	 * Gets the screenshot directory.
	 *
	 * @return the screenshot directory
	 */
	public String getScreenshotDirectory() {
		return screenshotDir;
	}

	/**
	 * Saves a screenshot of current plot as a PNG image. Image is saved using an
	 * unique time stamp as a file name in the directory specified by
	 * {@link #getScreenshotDirectory()}
	 * 
	 *
	 * @return true, if successful
	 * @throws IllegalArgumentException if {@link #getView()} is null
	 */
	public boolean saveScreenshot() throws IllegalArgumentException {
		if (!chartExists()) {
			throw new IllegalArgumentException("Viewer is not visible");
		}
		final String file = new SimpleDateFormat("'SNT 'yyyy-MM-dd HH-mm-ss'.png'").format(new Date());
		try {
			final File f = new File(screenshotDir, file);
			SNT.log("Saving snapshot to " + f);
			chart.screenshot(f);
		} catch (final IOException e) {
			SNT.error("IOException", e);
			return false;
		}
		return true;
	}

	/**
	 * Loads an OBJ file (Experimental). Note that some meshes may not be supported
	 * or rendered properly.
	 *
	 * @param filePath the absolute file path (or URL) of the file to be imported
	 * @param color    the color to render imported file
	 * @return true, if file was successfully loaded
	 * @throws IllegalArgumentException if {@link #getView()} is null
	 */
	public boolean loadOBJ(final String filePath, final java.awt.Color color) throws IllegalArgumentException {
		if (getView() == null) {
			throw new IllegalArgumentException("Viewer is not available");
		}
		final OBJFileLoader loader = new OBJFileLoader((filePath.startsWith("http")) ? filePath : "file://" + filePath);
		final DrawableVBO drawable = new DrawableVBO(loader);
		drawable.setColor(fromAWTColor(color));
		drawable.setQuality(chart.getQuality());
		final int nElemens = getSceneElements().size();
		chart.getScene().add(drawable);
		return getSceneElements().size() > nElemens;
	}

	private List<AbstractDrawable> getSceneElements() {
		return chart.getScene().getGraph().getAll();
	}

	/**
	 * Returns this plot's {@link View} holding {@link Scene}, {@link LightSet},
	 * {@link ICanvas}, etc.
	 * 
	 * @return this plot's View, or null if it was disposed after {@link #show()}
	 *         has been called
	 */
	public View getView() {
		return (chart == null) ? null : view;
	}


	private class MouseController extends AWTCameraMouseController {

		private final float PAN_FACTOR = 1f; // lower values mean more responsive pan
		private boolean panDone;
		private Coord3d prevMouse3d;


		public MouseController(final Chart chart) {
			super(chart);
		}


		private int getY(final MouseEvent e) {
			return -e.getY() + chart.getCanvas().getRendererHeight();
		}

		private void rotateLive(final Coord2d move) {
			rotate(move, true);
		}

		/* see AWTMousePickingPan2dController */
		public void pan(final Coord3d from, final Coord3d to) {
			final BoundingBox3d viewBounds = view.getBounds();
			final Coord3d offset = to.sub(from).div(-PAN_FACTOR);
			final BoundingBox3d newBounds = viewBounds.shift(offset);
			view.setBoundManual(newBounds);
			view.shoot();
			fireControllerEvent(ControllerType.PAN, offset);
		}

		public void zoom(final float factor) {
			final BoundingBox3d viewBounds = view.getBounds();
			final BoundingBox3d newBounds = viewBounds.scale(new Coord3d(factor, factor, factor));
			view.setBoundManual(newBounds);
			view.shoot();
			fireControllerEvent(ControllerType.ZOOM, factor);
		}

		public void snapToNextView() {
			final ViewPositionMode[] modes = { ViewPositionMode.FREE, ViewPositionMode.PROFILE, ViewPositionMode.TOP };
			final String[] descriptions = { "Unconstrained", "Side Constrained", "Top Constrained" };
			final ViewPositionMode currentView = chart.getViewMode();
			int nextViewIdx = 0;
			for (int i = 0; i < modes.length; i++) {
				if (modes[i] == currentView) {
					nextViewIdx = i + 1;
					break;
				}
			}
			if (nextViewIdx == modes.length)
				nextViewIdx = 0;
			stopThreadController();
			chart.setViewMode(modes[nextViewIdx]);
			displayMsg("View Mode: " + descriptions[nextViewIdx]);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController#
		 * mousePressed(java.awt.event.MouseEvent)
		 */
		@Override
		public void mousePressed(final MouseEvent e) {
			if (e.isControlDown() && AWTMouseUtilities.isLeftDown(e)) {
				snapToNextView();
			} else {
				super.mousePressed(e);
			}
			prevMouse3d = view.projectMouse(e.getX(), getY(e));
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController#
		 * mouseWheelMoved(java.awt.event.MouseWheelEvent)
		 */
		@Override
		public void mouseWheelMoved(final MouseWheelEvent e) {
			stopThreadController();
			final float factor = 1 + (e.getWheelRotation() / 10.0f);
			zoom(factor);
			prevMouse3d = view.projectMouse(e.getX(), getY(e));
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController#
		 * mouseDragged(java.awt.event.MouseEvent)
		 */
		@Override
		public void mouseDragged(final MouseEvent e) {

			final Coord2d mouse = xy(e);

			// Rotate on left-click
			if (AWTMouseUtilities.isLeftDown(e)) {
				final Coord2d move = mouse.sub(prevMouse).div(100);
				rotate(move);
			}

			// Pan on right-click
			else if (AWTMouseUtilities.isRightDown(e)) {
				final Coord3d thisMouse3d = view.projectMouse(e.getX(), getY(e));
				if (!panDone) { // 1/2 pan for cleaner rendering
					pan(prevMouse3d, thisMouse3d);
					panDone = true;
				} else {
					panDone = false;
				}
				prevMouse3d = thisMouse3d;
			}
			prevMouse = mouse;
		}
	}


	private class KeyController extends AbstractCameraController implements KeyListener {

		private static final float STEP = 0.1f;


		public KeyController(final Chart chart) {
			register(chart);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.awt.event.KeyListener#keyPressed(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyPressed(final KeyEvent e) {
			switch (e.getKeyChar()) {
			case 'a':
			case 'A':
				chart.setAxeDisplayed(!view.isAxeBoxDisplayed());
				break;
			case 'd':
			case 'D':
				toggleDarkMode();
				break;
			case 'h':
			case 'H':
			case '?':
				showHelp();
				break;
			// This only works at initialization, so skip for now
//			case 'q':
//			case 'Q':
//				changeQuality();
//				break;
			case 'm':
			case 'M':
				changeCameraMode();
				break;
			case 'r':
			case 'R':
				chart.setViewPoint(View.DEFAULT_VIEW);
				chart.setViewMode(ViewPositionMode.FREE);
				view.setBoundMode(ViewBoundMode.AUTO_FIT);
				displayMsg("View reset");
				break;
			case 's':
			case 'S':
				saveScreenshot();
				displayMsg("Screenshot taken");
				break;
			case 'v':
			case 'V':
				showVisibilityList();
				break;
			case '+':
			case '=':
				mouseControler.zoom(0.9f);
				break;
			case '-':
			case '_':
				mouseControler.zoom(1.1f);
				break;
			default:
				switch (e.getKeyCode()) {
				case KeyEvent.VK_DOWN:
					mouseControler.rotateLive(new Coord2d(0f, -STEP));
					break;
				case KeyEvent.VK_UP:
					mouseControler.rotateLive(new Coord2d(0f, STEP));
					break;
				case KeyEvent.VK_LEFT:
					mouseControler.rotateLive(new Coord2d(-STEP, 0));
					break;
				case KeyEvent.VK_RIGHT:
					mouseControler.rotateLive(new Coord2d(STEP, 0));
					break;
				default:
					break;
				}
			}
		}

		private void showVisibilityList() {
			final Object[] keys = plottedTrees.keySet().toArray(new Object[plottedTrees.size() + 1]);
			keys[plottedTrees.size()] = CheckBoxList.ALL_ENTRY;
			final CheckBoxList list = new CheckBoxList(keys);
			list.setCheckBoxListSelectedValue(CheckBoxList.ALL_ENTRY, false);
			list.getCheckBoxListSelectionModel().addListSelectionListener(new ListSelectionListener() {
				public void valueChanged(final ListSelectionEvent e) {
					if (!e.getValueIsAdjusting()) {
						@SuppressWarnings("unchecked")
						final List<String> selectedKeys = (List<String>) (List<?>) Arrays
								.asList(list.getCheckBoxListSelectedValues());
						plottedTrees.forEach((k, surface) -> {
							surface.setDisplayed(selectedKeys.contains(k));
						});
						view.shoot();
					}
				}
			});
			final JScrollPane choicesScrollPane = new JScrollPane(list);
			choicesScrollPane.setBorder(null);
			choicesScrollPane.setViewportView(list);
			final JDialog dialog = new JDialog(frame, "Object Visibility");
			dialog.add(choicesScrollPane);
			list.repaint();
			choicesScrollPane.revalidate();
			dialog.pack();
			dialog.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					list.setCheckBoxListSelectedValue(CheckBoxList.ALL_ENTRY, false);
					displayMsg("Visibility List closed. All objects now visible...");
				}
			});
			dialog.setLocationRelativeTo(frame);
			dialog.setVisible(true);
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.awt.event.KeyListener#keyTyped(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyTyped(final KeyEvent e) {
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.awt.event.KeyListener#keyReleased(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyReleased(final KeyEvent e) {
		}

		private void changeCameraMode() {
			final CameraMode newMode = (view.getCameraMode() == CameraMode.ORTHOGONAL) ? CameraMode.PERSPECTIVE
					: CameraMode.ORTHOGONAL;
			view.setCameraMode(newMode);
			final String mode = (newMode == CameraMode.ORTHOGONAL) ? "Orthogonal" : "Perspective";
			displayMsg("Camera mode changed to \"" + mode + "\"");
		}

		@SuppressWarnings("unused")
		private void changeQuality() {
			final Quality[] levels = { Quality.Fastest, Quality.Intermediate, Quality.Advanced, Quality.Nicest };
			final String[] grades = { "Fastest", "Intermediate", "High", "Best" };
			final Quality currentLevel = chart.getQuality();
			int nextLevelIdx = 0;
			for (int i = 0; i < levels.length; i++) {
				if (levels[i] == currentLevel) {
					nextLevelIdx = i + 1;
					break;
				}
			}
			if (nextLevelIdx == levels.length)
				nextLevelIdx = 0;
			chart.setQuality(levels[nextLevelIdx]);
			displayMsg("Quality level changed to '" + grades[nextLevelIdx] + "'");
		}

		private void toggleDarkMode() {
			if (chart == null)
				return;
			Color newForeground;
			Color newBackground;
			if (view.getBackgroundColor() == Color.BLACK) {
				newForeground = Color.BLACK;
				newBackground = Color.WHITE;
				setColorbarColors(false);
			} else {
				newForeground = Color.WHITE;
				newBackground = Color.BLACK;
				setColorbarColors(true);
			}
			view.setBackgroundColor(newBackground);
			view.getAxe().getLayout().setGridColor(newForeground);
			view.getAxe().getLayout().setMainColor(newForeground);
			for (final AbstractDrawable element : getSceneElements()) {
				if (element instanceof Shape) {
					final Shape shape = (Shape) element;
					if (shape.getColor() == newBackground) {
						shape.setColor(newForeground);
						continue;
					}
					for (int i = 0; i < shape.size(); i++) {
						if (shape.get(i) instanceof LineStrip) {
							final List<Point> points = ((LineStrip) shape.get(i)).getPoints();
							points.stream().forEach(p -> {
								if (p.getColor() == newBackground) {
									p.setColor(newForeground);
								}
							});
						}
					}
				}
			}
		}

		private void showHelp() {
			final StringBuffer sb = new StringBuffer("<HTML>");
			sb.append("<table>");
			sb.append("  <tr>");
			sb.append("    <td>Pan</td>");
			sb.append("    <td>Right-click &amp; drag</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Rotate</td>");
			sb.append("    <td>Left-click &amp; drag (or arrow keys)</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Scale</td>");
			sb.append("    <td>Scroll (or '+'/'-')</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Animate</td>");
			sb.append("    <td>Double left-click</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Snap to Position</td>");
			sb.append("    <td>Ctrl + left-click</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle Axes</td>");
			sb.append("    <td>Press 'A'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle Dark Mode</td>");
			sb.append("    <td>Press 'D'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle Camera Mode </td>");
			sb.append("    <td>Press 'M'</td>");
			sb.append("  </tr>");
//			sb.append("  <tr>");
//			sb.append("    <td>Quality Levels Loop</td>");
//			sb.append("    <td>Press 'Q'</td>");
//			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Reset</td>");
			sb.append("    <td>Press 'R'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Screenshot</td>");
			sb.append("    <td>Press 'S'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Objects Visibility</td>");
			sb.append("    <td>Press 'V'</td>");
			sb.append("  </tr>");
			sb.append("</table>");
			displayMsg(sb.toString(), 9000);
		}
	}

	/**
	 * Sets the line thickness for rendering {@link Tree}s that have no specified
	 * radius.
	 *
	 * @param thickness the new line thickness. Note that this value only applies to
	 *                  Paths that have no specified radius
	 */
	public void setLineThickness(final float thickness) {
		this.thickness = thickness;
	}

	/**
	 * Returns the default line thickness.
	 *
	 * @return the default line thickness used to render Paths without radius
	 */
	public float getLineThickness() {
		return thickness;
	}


	/* IDE debug method */
	public static void main(final String[] args) throws InterruptedException {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		//ij.ui().showUI();
		SNT.setDebugMode(true);
		final Tree tree = new Tree("/home/tferr/code/OP_1/OP_1.swc");
		final TreeColorizer colorizer = new TreeColorizer(ij.getContext());
		colorizer.colorize(tree, TreeColorizer.BRANCH_ORDER, ColorTables.ICE);
		final double[] bounds = colorizer.getMinMax();
		final TreePlot3D jzy3D = new TreePlot3D();
		jzy3D.add(tree);
		jzy3D.addColorBarLegend(ColorTables.ICE, (float) bounds[0], (float) bounds[1]);
		jzy3D.show();
	}

}
