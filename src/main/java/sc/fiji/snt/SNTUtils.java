/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.scijava.Context;
import org.scijava.log.LogService;
import org.scijava.table.GenericTable;
import org.scijava.ui.UIService;
import org.scijava.ui.console.ConsolePane;
import org.scijava.util.VersionUtils;

import fiji.util.Levenshtein;
import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.Colors;
import ij.plugin.ContrastEnhancer;
import ij.plugin.ZProjector;
import ij.process.ImageConverter;
import ij.process.StackConverter;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.viewer.Viewer3D;

/** Static utilities for SNT **/
public class SNTUtils {

	private static Context context;
	private static LogService logService;
	private static UIService uiService;

	public static final String VERSION = getVersion();

	private static boolean initialized;
	private static SNT plugin;
	private static HashMap<Integer, Viewer3D> viewerMap;

	private SNTUtils() {}

	private synchronized static void initialize() {
		if (initialized) return;
		if (context == null) context = (Context) IJ.runPlugIn("org.scijava.Context",
			"");
		if (logService == null) logService = context.getService(LogService.class);
		if (uiService == null) uiService = context.getService(UIService.class);
		initialized = true;
	}

	public static String getReadableVersion() {
		if (VERSION.length() < 21) return "SNT " + VERSION;
		return "SNT " + VERSION.substring(0, 21) + "...";
	}

	private static String getVersion() {
		return VersionUtils.getVersion(SNT.class);
	}

	public static synchronized void addViewer(final Viewer3D viewer) {
		if (viewerMap == null) viewerMap = new HashMap<>();
		viewerMap.put(viewer.getID(), viewer);
	}

	public static synchronized void removeViewer(final Viewer3D viewer) {
		if (viewerMap != null && viewer != null) {
			viewerMap.values().removeIf(viewer::equals);
			if (plugin != null && plugin.getUI() != null) {
				final Viewer3D v = plugin.getUI().getReconstructionViewer(false);
				if (v != null && v.getID() == viewer.getID()) {
					plugin.getUI().setReconstructionViewer(null);
				}
			}
		}
	}

	protected static HashMap<Integer, Viewer3D> getViewers() {
		return viewerMap;
	}

	protected static synchronized void error(final String string) {
		if (SNTUtils.isDebugMode()) nonDebugError(string);
	}

	protected static void setPlugin(final SNT plugin) {
		SNTUtils.plugin = plugin;
		if (context == null && plugin != null) context = plugin.getContext();
	}

	public static SNT getPluginInstance() {
		return plugin;
	}

	protected static synchronized void nonDebugError(final String string) {
		if (!initialized) initialize();
		logService.error("[SNT] " + string);
	}

	public static synchronized void error(final String string,
		final Throwable t)
	{
		if (!SNTUtils.isDebugMode()) return;
		if (!initialized) initialize();
		logService.error("[SNT] " + string, t);
	}

	public static synchronized void log(final String string) {
		if (!SNTUtils.isDebugMode()) return;
		if (!initialized) initialize();
		logService.info("[SNT] " + string);
	}

	protected static synchronized void warn(final String string) {
		if (!SNTUtils.isDebugMode()) return;
		if (!initialized) initialize();
		logService.warn("[SNT] " + string);
	}

	protected static void convertTo32bit(final ImagePlus imp) throws IllegalArgumentException {
		if (imp.getBitDepth() == 32)
			return;
		if (imp.getNSlices() == 1)
			new ImageConverter(imp).convertToGray32();
		else
			new StackConverter(imp).convertToGray32();
	}

	public static void convertTo8bit(final ImagePlus imp) {
		if (imp.getType() != ImagePlus.GRAY8) {
			final boolean doScaling = ImageConverter.getDoScaling();
			ImageConverter.setDoScaling(true);
			new ImageConverter(imp).convertToGray8();
			ImageConverter.setDoScaling(doScaling);
		}
	}

	public static ImagePlus getMIP(final ImagePlus imp) {
		final ImagePlus mip = ZProjector.run(imp, "max");
		new ContrastEnhancer().stretchHistogram(mip, 0.35);
		return mip;
	}

	public static void csvQuoteAndPrint(final PrintWriter pw, final Object o) {
		pw.print(stringForCSV("" + o));
	}

	private static String stringForCSV(final String s) {
		boolean quote = false;
		String result = s;
		if (s.indexOf(',') >= 0 || s.indexOf(' ') >= 0) quote = true;
		if (s.indexOf('"') >= 0) {
			quote = true;
			result = s.replaceAll("\"", "\"\"");
		}
		if (quote) return "\"" + result + "\"";
		else return result;
	}

	protected static String getColorString(final Color color) {
		//String name = "none";
		//name = Colors.getColorName(color, name);
		//if (!"none".equals(name)) name = Colors.colorToString(color);
		return Colors.colorToString2(color);
	}

	protected static Color getColor(String colorName) {
		if (colorName == null) colorName = "none";
		Color color = null;
		color = Colors.getColor(colorName, color);
		if (color == null) color = Colors.decode(colorName, color);
		return color;
	}

	public static String stripExtension(final String filename) {
		final int lastDot = filename.lastIndexOf(".");
		return (lastDot > 0) ? filename.substring(0, lastDot) : filename;
	}

	public static void saveTable(final GenericTable table, final File outputFile) throws IOException {
		final String sep = ",";
		final PrintWriter pw = new PrintWriter(
				new OutputStreamWriter(new FileOutputStream(outputFile.getAbsolutePath()), StandardCharsets.UTF_8));
		final int columns = table.getColumnCount();
		final int rows = table.getRowCount();

		// Print a column header to hold row headers
		SNTUtils.csvQuoteAndPrint(pw, "Description");
		pw.print(sep);
		for (int col = 0; col < columns; ++col) {
			SNTUtils.csvQuoteAndPrint(pw, table.getColumnHeader(col));
			if (col < (columns - 1))
				pw.print(sep);
		}
		pw.print("\r\n");
		for (int row = 0; row < rows; row++) {
			SNTUtils.csvQuoteAndPrint(pw, table.getRowHeader(row));
			pw.print(sep);
			for (int col = 0; col < columns; col++) {
				SNTUtils.csvQuoteAndPrint(pw, table.get(col, row));
				if (col < (columns - 1))
					pw.print(sep);
			}
			pw.print("\r\n");
		}
		pw.close();
	}

	protected static boolean fileAvailable(final File file) {
		try {
			return file != null && file.exists();
		}
		catch (final SecurityException ignored) {
			return false;
		}
	}

	protected static boolean isValidURL(final String url) {
		try {
			new URL(url).toURI();
			return true;
		}
		catch (final Exception e) {
			return false;
		}
	}

	public static String formatDouble(final double value, final int digits) {
		return (Double.isNaN(value)) ? "NaN" : getDecimalFormat(value, digits).format(value);
	}

	public static DecimalFormat getDecimalFormat(final double value, final int digits) {
		StringBuilder pattern = new StringBuilder("0.");
		while (pattern.length() < digits + 2)
			pattern.append("0");
		final double absValue = Math.abs(value);
		if ((absValue > 0 && absValue < 0.01) || absValue >= 1000) pattern.append("E0");
		final NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
		final DecimalFormat df = (DecimalFormat)nf;
		return df;
	}

	/**
	 * Assesses if SNT is running in debug mode
	 *
	 * @return the debug flag
	 */
	public static boolean isDebugMode() {
		return SNT.verbose;
	}

	/**
	 * Enables/disables debug mode
	 *
	 * @param b verbose flag
	 */
	public static void setDebugMode(final boolean b) {
		if (isDebugMode() && !b) {
			log("Exiting debug mode...");
		}
		SNT.verbose = b;
		if (isDebugMode()) {
			log("Entering debug mode..."); // will initialize uiService
			final ConsolePane<?> console = uiService.getDefaultUI().getConsolePane();
			if (console != null) console.show();
		}
	}

	public static File findClosestPair(final File file, final String pairExt) {
		try {
			SNTUtils.log("Finding closest pair for " + file);
			final File dir = file.getParentFile();
			final String[] list = dir.list((f, s) -> s.endsWith(pairExt));
			SNTUtils.log("Found " + list.length + " " + pairExt + " files");
			if (list.length == 0) return null;
			Arrays.sort(list);
			String dirPath = dir.getAbsolutePath();
			if (!dirPath.endsWith(File.separator)) dirPath += File.separator;
			int cost = Integer.MAX_VALUE;
			final String seed = stripExtension(file.getName().toLowerCase());
			String closest = null;
			final Levenshtein levenshtein = new Levenshtein(5, 10, 1, 5, 5, 0);
			for (final String item : list) {
				final String filename = stripExtension(Paths.get(item).getFileName()
					.toString()).toLowerCase();
				final int currentCost = levenshtein.cost(seed, filename);
				SNTUtils.log("Levenshtein cost for '" + item + "': " + currentCost);
				if (currentCost <= cost) {
					cost = currentCost;
					closest = item;
				}
			}
			SNTUtils.log("Identified pair '" + closest + "'");
			return new File(dirPath + closest);
		}
		catch (final SecurityException | NullPointerException ignored) {
			return null;
		}
	}

	public static boolean similarCalibrations(final Calibration a,
		final Calibration b)
	{
		double ax = 1, ay = 1, az = 1;
		double bx = 1, by = 1, bz = 1;
		String aunit = "", bunit = "";
		if (a != null) {
			ax = a.pixelWidth;
			ay = a.pixelHeight;
			az = a.pixelDepth;
			aunit = a.getUnit();
		}
		if (b != null) {
			bx = b.pixelWidth;
			by = b.pixelHeight;
			bz = b.pixelDepth;
			bunit = a.getUnit();
		}
		if (!aunit.equals(bunit)) return false;
		final double epsilon = 0.000001;
		final double pixelWidthDifference = Math.abs(ax - bx);
		if (pixelWidthDifference > epsilon) return false;
		final double pixelHeightDifference = Math.abs(ay - by);
		if (pixelHeightDifference > epsilon) return false;
		final double pixelDepthDifference = Math.abs(az - bz);
		return !(pixelDepthDifference > epsilon);
	}

	protected static String getSanitizedUnit(final String unit) {
		final BoundingBox bd = new BoundingBox();
		bd.setUnit(unit);
		return bd.getUnit();
	}

	/**
	 * Generates a list of random paths. Only useful for debugging purposes
	 *
	 * @return the list of random Paths
	 */
	public static List<Path> randomPaths() {
		final List<Path> data = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			final Path p = new Path(1, 1, 1, "unit");
			final double v1 = new Random().nextGaussian();
			final double v2 = new Random().nextGaussian();
			p.addPointDouble(v1, v2, v1);
			p.addPointDouble(v2, v1, v2);
			data.add(p);
		}
		return data;
	}

}
