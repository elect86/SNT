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

package sc.fiji.snt.gui.cmds;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;
import org.scijava.widget.FileWidget;

import net.imagej.ImageJ;
import sc.fiji.snt.viewer.Viewer3D;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

/**
 * Command for loading Reconstruction files in Reconstruction Viewer. Loaded
 * paths are not listed in the Path Manager.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer = "init",
	label = "Load Reconstruction(s)...")
public class LoadReconstructionCmd extends CommonDynamicCmd {

	private static final String COLOR_CHOICE_MONO =
		"Common color specified below";
	private static final String COLOR_CHOICE_POLY =
		"Distinct (each file labelled uniquely)";

	private static final String LAST_DIR_KEY = "lastDir";
	private static final String LAST_FILE_KEY = "lastFile";

	@Parameter
	private PrefService prefService;

	@Parameter(label = "File", required = true, persist = false,
		description = "Supported extensions: traces, (e)SWC, json")
	private File file;

	@Parameter(label = "Filenames containing", required = false, //
			description = "<html>Only files containing this string will be considered." +
				"<br>Leave blank to consider all reconstruction files in the directory.")
	private String pattern;

	@Parameter(required = false, label = "Color", choices = { COLOR_CHOICE_MONO,
		COLOR_CHOICE_POLY })
	private String colorChoice;

	@Parameter(label = "<HTML>&nbsp;", required = false,
		description = "Rendering color of imported file(s)")
	private ColorRGB color;

	@Parameter(label = "Dendrites and axons as separate objects", required = false,
		description = "If the file contains an axonal and dendritic arbor load each arbor individually.")
	private boolean splitByType;

	@Parameter(persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg;

	@Parameter(required = false)
	private Viewer3D recViewer;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean importDir = false;

	protected void init() {
		if (importDir) {
			final MutableModuleItem<File> fileMitem = getInfo().getMutableInput(
				"file", File.class);
			fileMitem.setWidgetStyle(FileWidget.DIRECTORY_STYLE);
			fileMitem.setLabel("Directory");
			fileMitem.setDescription(
				"<HTML>Path to directory containing multiple files." +
					"<br>Supported extensions: traces, (e)SWC, json");
			String lastUsedDir = prefService.get(LoadReconstructionCmd.class, LAST_DIR_KEY);
			if (lastUsedDir != null) new File(lastUsedDir);
		}
		else {
			// hide the 'pattern' field
			final MutableModuleItem<String> patternMitem = getInfo().getMutableInput(
					"pattern", String.class);
			patternMitem.setVisibility(ItemVisibility.INVISIBLE);
			resolveInput("pattern");
		}
		if (recViewer != null && recViewer.isSNTInstance()) {
			msg = "NB: Loaded file(s) will not be listed in Path Manager";
		}
		else if (importDir) {
			msg = "NB: You can also drag & drop directories into viewer or 'RV Controls'";
		} else {
			msg = " ";
		}
		populateLastUsedFile();
	}

	private void populateLastUsedFile() {
		final String lastUsedFile = prefService.get(LoadReconstructionCmd.class, (importDir) ? LAST_DIR_KEY : LAST_FILE_KEY);
		if (lastUsedFile != null) file = new File(lastUsedFile);
	}

	private void setLastUsedFile() {
		prefService.put(LoadReconstructionCmd.class, (importDir) ? LAST_DIR_KEY : LAST_FILE_KEY, file.getAbsolutePath());
	}

	private ColorRGB getNonNullColor() {
		if (color == null) {
			return (recViewer.isDarkModeOn()) ? Colors.WHITE : Colors.BLACK;
		}
		return color;
	}

	/*
	 *
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		try {
			if (recViewer == null)
				recViewer = sntService.getRecViewer();
		} catch (final UnsupportedOperationException exc) {
			error("SNT's Reconstruction Viewer is not open and no other Viewer was specified.");
		}

		if (!file.exists())
			error(file.getAbsolutePath() + " is no longer available");

		notifyLoadingStart(recViewer);
		final boolean splitState = recViewer.isSplitDendritesFromAxons();
		recViewer.setSplitDendritesFromAxons(splitByType);
		final String importColor = (colorChoice.contains("unique")) ? null : getNonNullColor().toHTMLColor();
		if (file.isFile()) {
			try {
				final Collection<Tree> trees = Tree.listFromFile(file.getAbsolutePath());
				if (trees == null || trees.isEmpty())
					cancel("No Paths could be extracted from file. Invalid path?");
				recViewer.addTrees(trees, importColor);
				recViewer.validate();
				return;
			} catch (final IllegalArgumentException ex) {
				cancel(ex.getMessage());
			}
		}

		if (file.isDirectory()) {
			final File[] treeFiles = SNTUtils.getReconstructionFiles(file, pattern);
			if (treeFiles.length == 0) {
				error("Directory does not contain valid reconstructions");
			}
			recViewer.addTrees(treeFiles, importColor);
		}
		recViewer.setSplitDendritesFromAxons(splitState);
		setLastUsedFile();
		notifyLoadingEnd(false, recViewer);
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		input.put("importDir", true);
		ij.command().run(LoadReconstructionCmd.class, true, input);
	}
}
