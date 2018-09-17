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
package tracing.analysis.sholl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import sholl.Profile;
import sholl.ProfileEntry;
import sholl.ShollUtils;
import sholl.UPoint;
import sholl.math.LinearProfileStats;
import sholl.parsers.Parser;
import tracing.Path;
import tracing.Tree;
import tracing.plugin.DistributionCmd;
import tracing.util.PointInImage;

/**
 * A {@link Parser} for extracting Sholl Profiles from a {@link Tree}.
 *
 * @author Tiago Ferreira
 */
public class TreeParser implements Parser {

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * all primary Paths.
	 */
	public static final int PRIMARY_NODES_ANY = 0;

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * Paths tagged as Apical Dendrite.
	 */
	public static final int PRIMARY_NODES_APICAL_DENDRITE = 1;

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * Paths tagged as Axon.
	 */
	public static final int PRIMARY_NODES_AXON = 2;

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * Paths tagged as Custom.
	 */
	public static final int PRIMARY_NODES_CUSTOM = 3;

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * Paths tagged as (Basal) Dendrite
	 */
	public static final int PRIMARY_NODES_DENDRITE = 4;

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * Paths tagged as Soma
	 */
	public static final int PRIMARY_NODES_SOMA = 5;

	/**
	 * Flag for defining the profile center as the average position of root nodes of
	 * Paths tagged as Undefined
	 */
	public static final int PRIMARY_NODES_UNDEFINED = 6;

	private final Tree tree;
	private List<ShollPoint> shollPointsList;
	private PointInImage center;
	private double stepSize = 0;
	private Profile profile;
	private volatile boolean running = true;
	private double[] squaredRangeStarts;
	private int[] crossingsPastEach;

	/**
	 * Instantiates a new Tree Parser.
	 *
	 * @param tree the Tree to be profiled
	 */
	public TreeParser(final Tree tree) {
		this.tree = tree;
	}

	/**
	 * Computes the center of the Profile.
	 *
	 * @param choice the flag specifying the center (e.g.,
	 *               {@link #PRIMARY_NODES_SOMA}, {@link #PRIMARY_NODES_ANY}, etc.)
	 * @throws IllegalArgumentException if choice is not a recognized flag or if no
	 *                                  Paths in the Tree match the choice criteria
	 */
	public void setCenter(final int choice) throws IllegalArgumentException {
		switch (choice) {
		case PRIMARY_NODES_ANY:
			center = getCenter(-1);
			break;
		case PRIMARY_NODES_UNDEFINED:
			center = getCenter(Path.SWC_UNDEFINED);
			break;
		case PRIMARY_NODES_SOMA:
			center = getCenter(Path.SWC_SOMA);
			break;
		case PRIMARY_NODES_AXON:
			center = getCenter(Path.SWC_AXON);
			break;
		case PRIMARY_NODES_DENDRITE:
			center = getCenter(Path.SWC_DENDRITE);
			break;
		case PRIMARY_NODES_APICAL_DENDRITE:
			center = getCenter(Path.SWC_APICAL_DENDRITE);
			break;
		case PRIMARY_NODES_CUSTOM:
			center = getCenter(Path.SWC_CUSTOM);
			break;
		default:
			throw new IllegalArgumentException("Center choice was not understood");
		}
		if (center == null)
			throw new IllegalArgumentException("Tree does not contain Paths matching specified choice");
	}

	/**
	 * Returns the center coordinates
	 *
	 * @return the point defining the center, or null if it has not yet been set.
	 */
	public UPoint getCenter() {
		return (center == null) ? null : new UPoint(center.x, center.y, center.z);
	}

	private PointInImage getCenter(final int swcType) {
		final List<PointInImage> points = new ArrayList<>();
		for (final Path p : tree.list()) {
			if (swcType < 0 || p.getSWCType() == swcType) {
				points.add(p.getPointInImage(0));
				continue;
			}
		}
		return (points.isEmpty()) ? null :PointInImage.average(points);
	}

	/**
	 * Sets the center of the profile.
	 *
	 * @param center the focal point of the profile
	 */
	public void setCenter(final PointInImage center) {
		if (successful())
			throw new UnsupportedOperationException("setCenter() must be called before parsing data");
		this.center = center;
	}

	/**
	 * Sets the radius step size.
	 *
	 * @param stepSize the radius step size
	 */
	public void setStepSize(final double stepSize) {
		if (successful())
			throw new UnsupportedOperationException("setStepSize() must be called before parsing data");
		this.stepSize = (stepSize < 0) ? 0 : stepSize;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#parse()
	 */
	@Override
	public void parse() {
		if (tree == null || tree.isEmpty()) {
			throw new IllegalArgumentException("Invalid tree");
		}
		if (center == null) {
			throw new IllegalArgumentException("Data cannot be parsed unless a center is specified");
		}
		profile = new Profile();
		if (tree.getLabel() != null)
			profile.setIdentifier(tree.getLabel());
		profile.setNDimensions((tree.is3D())?3:2);
		profile.setCenter(center.toUPoint());
		if (tree.getBoundingBox(false) != null)
			profile.setSpatialCalibration(tree.getBoundingBox(false).getCalibration());
		profile.getProperties().setProperty(KEY_SOURCE, SRC_TRACES);
		assembleSortedShollPointList();
		assembleProfile();
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#successful()
	 */
	@Override
	public boolean successful() {
		return profile != null && profile.size() > 0;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#terminate()
	 */
	@Override
	public void terminate() {
		running = false;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#getProfile()
	 */
	@Override
	public Profile getProfile() {
		return profile;
	}

	private void assembleSortedShollPointList() {
		shollPointsList = new ArrayList<>();
		tree.list().stream().forEach(p -> {
			if (!running)
				return;
			for (int i = 0; i < p.size() - 1; ++i) {
				final PointInImage pim1 = p.getPointInImage(i);
				final PointInImage pim2 = p.getPointInImage(i + 1);
				final double distanceSquaredFirst = pim1.distanceSquaredTo(center);
				final double distanceSquaredSecond = pim2.distanceSquaredTo(center);
				shollPointsList.add(new ShollPoint(distanceSquaredFirst, distanceSquaredFirst < distanceSquaredSecond));
				shollPointsList
						.add(new ShollPoint(distanceSquaredSecond, distanceSquaredFirst >= distanceSquaredSecond));
			}
		});
		// Ensure we are not keeping duplicated data points
		shollPointsList = shollPointsList.stream().distinct().collect(Collectors.toList());
		Collections.sort(shollPointsList);
	}

	private void assembleProfile() {
		final int n = shollPointsList.size();
		squaredRangeStarts = new double[n];
		crossingsPastEach = new int[n];
		int currentCrossings = 0;
		Collections.sort(shollPointsList);
		for (int i = 0; i < n; ++i) {
			final ShollPoint p = shollPointsList.get(i);
			if (p.nearer)
				++currentCrossings;
			else
				--currentCrossings;
			squaredRangeStarts[i] = p.distanceSquared;
			crossingsPastEach[i] = currentCrossings;
		}
		if (!running)
			return;
		int nSamples;
		if (stepSize > 0) { // Discontinuous sampling

			final double maxDistance = Math.sqrt(squaredRangeStarts[n - 1]);
			nSamples = (int) Math.ceil(maxDistance / stepSize);
			for (int i = 0; i < nSamples; ++i) {
				final double x = i * stepSize;
				final double y = crossingsAtDistanceSquared(x * x);
				final ProfileEntry entry = new ProfileEntry(x, y, null);
				profile.add(entry);
			}

		} else { // Continuous sampling

			nSamples = squaredRangeStarts.length;
			for (int i = 0; i < nSamples; ++i) {
				final double x = Math.sqrt(squaredRangeStarts[i]);
				final double y = crossingsAtDistanceSquared(squaredRangeStarts[i]);
				final ProfileEntry entry = new ProfileEntry(x, y, null);
				profile.add(entry);
			}
		}

	}

	private int crossingsAtDistanceSquared(final double distanceSquared) {
		int minIndex = 0;
		int maxIndex = squaredRangeStarts.length - 1;
		if (distanceSquared < squaredRangeStarts[minIndex])
			return 1;
		else if (distanceSquared > squaredRangeStarts[maxIndex])
			return 0;
		while (maxIndex - minIndex > 1) {
			final int midPoint = (maxIndex + minIndex) / 2;
			if (distanceSquared < squaredRangeStarts[midPoint])
				maxIndex = midPoint;
			else
				minIndex = midPoint;
		}
		return crossingsPastEach[minIndex];
	}

	private class ShollPoint implements Comparable<ShollPoint> {
		private final boolean nearer;
		private final double distanceSquared;

		ShollPoint(final double distanceSquared, final boolean nearer) {
			this.distanceSquared = distanceSquared;
			this.nearer = nearer;
		}

		@Override
		public int compareTo(final ShollPoint other) {
			if (this.distanceSquared < other.distanceSquared)
				return -1;
			else if (other.distanceSquared < this.distanceSquared)
				return 1;
			return 0;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(final Object o) {
			if (this == o)
				return true;
			if (o == null)
				return false;
			if (!(o instanceof ShollPoint))
				return false;
			final ShollPoint other = (ShollPoint) o;
			return (nearer == other.nearer && distanceSquared == other.distanceSquared);
		}
	}

	/**
	 * Gets the labels image.
	 *
	 * @param templateImg the template img
	 * @param cTable the c table
	 * @return the labels image
	 */
	public ImagePlus getLabelsImage(final ImagePlus templateImg, final ColorTable cTable) {
		if (templateImg == null)
			throw new IllegalArgumentException("Template image cannot be null");
		if (!successful() || crossingsPastEach == null || squaredRangeStarts == null || center == null)
			throw new UnsupportedOperationException("Data has not been parsed");
		final int width = templateImg.getWidth();
		final int height = templateImg.getHeight();
		final int depth = templateImg.getNSlices();
		final Calibration c = templateImg.getCalibration();
		double x_spacing = 1;
		double y_spacing = 1;
		double z_spacing = 1;
		if (c != null) {
			x_spacing = c.pixelWidth;
			y_spacing = c.pixelHeight;
			z_spacing = c.pixelDepth;
		}
		final ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; ++z) {
			final short[] pixels = new short[width * height];
			for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					final PointInImage point = new PointInImage(x_spacing * x, y_spacing * y, z_spacing * z);
					pixels[y * width + x] = (short) crossingsAtDistanceSquared(point.distanceSquaredTo(center));
				}
			}
			final ShortProcessor sp = new ShortProcessor(width, height);
			sp.setPixels(pixels);
			stack.addSlice("", sp);
		}
		final ImagePlus result = new ImagePlus("Labels Image", stack);
		result.setLut(ShollUtils.getLut((cTable == null) ? ColorTables.ICE : cTable));
		result.setDisplayRange(0,  new LinearProfileStats(profile).getMax());
		result.setCalibration(templateImg.getCalibration());
		return result;
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final Tree tree = new Tree(DistributionCmd.randomPaths());
		final TreeParser parser = new TreeParser(tree);
		parser.setCenter(PRIMARY_NODES_ANY);
		parser.parse();
		parser.getProfile().plot().show();
	}

}
