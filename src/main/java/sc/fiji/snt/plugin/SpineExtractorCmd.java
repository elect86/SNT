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

package sc.fiji.snt.plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import sc.fiji.snt.NearPoint;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.PointInCanvas;

/**
 * Command for obtaining spine/varicosity counts from multi-point ROIs
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, label = "Extract Spine/Varicosity Counts from ROI(s)...", initializer = "init")
public class SpineExtractorCmd extends CommonDynamicCmd {
	
	private static final String MSG = "<br>To initiate counts, right-click on image and run \"Count Spine/Varicosities...\"";

	@Parameter(required = false, label = "Source of Multi-point ROI(s):") // choices set by #init()
	private String roiSource;

	@Parameter(label = "Max. association distance", min = "-1", //
			description = "<HTML>The maximum allowed distance between a point and its path<br>"
					+ "Set it to -1 to disable this option.")
	private double maxDist;

	@Parameter(label = "Add extracted counts to ROI Manager", //
			description = "<HTML>Generates new ROIs from the assigned counts and adds them to the ROI Manager.<br>"
					+ "This allows you to validate the extraction and ensure the assigments are correct.")
	private boolean addToManager;

	@Parameter(required = true)
	private Collection<Path> paths;

	private ImagePlus imp;
	private RoiManager rm;


	@SuppressWarnings("unused")
	private void init() {
		super.init(true);
		imp = snt.getImagePlus();
		rm = RoiManager.getInstance();
		final MutableModuleItem<String> mItem = getInfo().getMutableInput("roiSource", String.class);
		final ArrayList<String> choices = new ArrayList<>(3);
		if (imp.getRoi() != null) choices.add("Active ROI");
		if (imp.getOverlay() != null) choices.add("Image Overlay");
		if (rm != null) choices.add("ROI Manager");
		if (choices.isEmpty()) {
			abort();
		} else {
			mItem.setChoices(choices);
		}
		final MutableModuleItem<Double> maxItem = getInfo().getMutableInput("maxDist", Double.class);
		maxItem.setLabel(maxItem.getLabel() + " (" + imp.getCalibration().getUnit() +")");
		if (maxDist == 0) maxDist = 5; // A sensible default?
	}

	private void abort() {
		resolveInput("roiSource");
		resolveInput("maxDist");
		resolveInput("addToManager");
		resolveInput("paths");
		error("No ROIs are available for extraction." + MSG);
	}

	@Override
	public void run() {
		final List<PointRoi> rois = getROIs();
		if (rois == null || rois.isEmpty())
			return; // error messages have been displayed

		final double voxelSize = getVoxelSize();
		double unscaledSqrtDistance;
		if (Double.isNaN(maxDist) || maxDist < 0)
			unscaledSqrtDistance = Double.MAX_VALUE / 2;
		else if (maxDist == 0)
			unscaledSqrtDistance = 1.25; // < Math.sqrt(2)
		else
			unscaledSqrtDistance = maxDist / voxelSize;

		final HashMap<Path, List<PointInCanvas>> pathsToPoints = new HashMap<>();

		for (final PointRoi roi : rois) {
			final FloatPolygon points2d = roi.getFloatPolygon();
			for (int i = 0; i < roi.size(); i++) {
				final PointInCanvas pic = new PointInCanvas(points2d.xpoints[i], points2d.ypoints[i],
						roi.getPointPosition(i));
				final NearPoint np = snt.getPathAndFillManager().nearestPointOnAnyPath(paths, pic, unscaledSqrtDistance);
				if (np != null && np.getPath() != null) {
					final List<PointInCanvas> pics = pathsToPoints.get(np.getPath());
					if (pics == null) {
						final List<PointInCanvas> list = new ArrayList<>();
						list.add(pic);
						pathsToPoints.put(np.getPath(), list);
					} else {
						pics.add(pic);
					}
				}
			}
		}

		pathsToPoints.forEach((path, listOfPoints) -> path.setSpineOrVaricosityCount(listOfPoints.size()));
		try {
			// Assign tags
			ui.getPathManager().applyDefaultTags("No. of Spines/Varicosities");
		} catch (final IllegalArgumentException ignore) {
			// do nothing
		}

		if (addToManager) {

			final TreeColorMapper mapper = new TreeColorMapper(getContext());
			mapper.map(new Tree(paths), TreeColorMapper.N_SPINES, ColorTables.ICE);
			if (ui != null) ui.getPathManager().update();

			if (rm == null) rm = new RoiManager();
			final int channel = imp.getC();
			final int frame = imp.getT();
			pathsToPoints.forEach((path, listOfPoints) -> {
				final PointRoi newRoi = new PointRoi();
				newRoi.setName(path.getName() + " Spines/Varic.");
				newRoi.setStrokeColor(path.getColor());
				//newRoi.setFillColor(path.getColor());
				newRoi.setShowLabels(true);
				newRoi.setPointType(PointRoi.DOT);
				newRoi.setSize(5);
				listOfPoints.forEach(pic -> {
					imp.setPositionWithoutUpdate(channel, (int) pic.z, frame);
					newRoi.addPoint(imp, pic.x, pic.y);
				});
				rm.addRoi(newRoi);
			});
			rm.setVisible(true);
		}
		resetUI();
	}

	private List<PointRoi> getROIs() {
		if (roiSource.contains("Active")) {
			if (imp == null || imp.getRoi() == null || !(imp.getRoi() instanceof PointRoi)) {
				error("No active multi-point ROI(s) exist." + MSG);
				return null;
			}
			return Collections.singletonList((PointRoi) imp.getRoi());
		} else if (roiSource.contains("Overlay")) {
			if (imp == null || imp.getOverlay() == null) {
				error("The image overlay is not accessible." + MSG);
				return null;
			}
			return assemblePointRoiList(imp.getOverlay().iterator(), "Image Overlay ");
		} else if (roiSource.contains("Manager")) {
			if (RoiManager.getInstance2() == null) {
				error("Roi Manager is not available. " + MSG);
				return null;
			}
			return assemblePointRoiList(RoiManager.getInstance2().iterator(), "Roi Manager");
		}
		return null;
	}

	private List<PointRoi> assemblePointRoiList(final Iterator<Roi> roiIterator, final String sourceDescription) {
		final List<PointRoi> points = new ArrayList<>();
		while (roiIterator.hasNext()) {
			final Roi roi = roiIterator.next();
			if (roi instanceof PointRoi)
				points.add((PointRoi) roi);
		}
		if (points.isEmpty()) {
			error(sourceDescription + " does not contain Multi-point ROIs." + MSG);
		}
		return points;
	}

	private double getVoxelSize() {
		if (imp.getNSlices() == 1)
			return (imp.getCalibration().pixelWidth + imp.getCalibration().pixelHeight) / 2;
		return (imp.getCalibration().pixelWidth + imp.getCalibration().pixelHeight + imp.getCalibration().pixelDepth) / 3;
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final SNTService sntService = ij.context().getService(SNTService.class);
		final Tree tree = sntService.demoTrees().get(0);
		final Map<String, Object> input = new HashMap<>();
		input.put("paths", tree.list());
		ij.command().run(SpineExtractorCmd.class, true, input);
	}
}