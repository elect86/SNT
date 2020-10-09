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

package sc.fiji.snt.analysis;

import ij.ImagePlus;
import ij.measure.Calibration;
import sc.fiji.analyzeSkeleton.*;
import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.Viewer3D;

import java.util.*;

/**
 * Class for generation of {@link Tree}s from a skeletonized {@link ImagePlus}.
 *
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 * @see sc.fiji.skeletonize3D.Skeletonize3D_
 * @see AnalyzeSkeleton_
 */
public class SkeletonConverter {

    // AnalyzeSkeleton parameters
    private final ImagePlus imp;
    private ImagePlus origIP = null;
    private int pruneMode = AnalyzeSkeleton_.SHORTEST_BRANCH;
    private boolean pruneEnds = false;
    private boolean shortestPath = false;
    private boolean silent = true;
    private boolean verbose = false;
    private boolean pruneByLength = false;
    private double lengthThreshold;
    // Scale parameters for generated reconstructions
    double pixelWidth;
    double pixelHeight;
    double pixelDepth;

    /**
     * @param imagePlus The image to be parsed. It is expected to be a topological
     *                  skeleton (non-zero foreground) (conversion will be
     *                  nonsensical otherwise).
     */
    public SkeletonConverter(final ImagePlus imagePlus) {
        this.imp = imagePlus;
        final Calibration cal = imp.getCalibration();
        this.pixelWidth = cal.pixelWidth;
        this.pixelHeight = cal.pixelHeight;
        this.pixelDepth = cal.pixelDepth;
    }

    /**
     * @param imagePlus   The image to be parsed. It is expected to be binary
     *                    (non-zero foreground).
     * @param skeletonize If true, image will be skeletonized using
     *                    {@link Skeletonize3D_} _in place_ prior to the analysis.
     *                    Conversion will be nonsensical if {@code false} and
     *                    {@code imagePlus} is not a topological skeleton
     * @throws IllegalArgumentException if {@code skeletonize} is true and
     *                                  {@code imagePlus} is not binary.
     */
    public SkeletonConverter(final ImagePlus imagePlus, final boolean skeletonize) throws IllegalArgumentException {
        this(imagePlus);
        if (skeletonize) {
            if (!imagePlus.getProcessor().isBinary())
                throw new IllegalArgumentException("Only binary images allowed");
            skeletonize(imagePlus);
        }
    }

    /**
     * Generates a list of {@link Tree}s from the skeleton image.
     * Each Tree corresponds to one connected component of the graph returned by {@link SkeletonResult#getGraph()}.
     *
     * @return the skeleton tree list
     */
    public List<Tree> getTrees() {
        final List<Tree> treeList = new ArrayList<>();
        for (final DirectedWeightedGraph graph : getGraphs()) {
            final Tree tree = graph.getTree();
            /* Assign image calibration to tree. Avoids unexpected offsets when initializing SNT */
            tree.assignImage(imp);
            treeList.add(tree);
        }
        return treeList;
    }

    /**
     * Generates a list of {@link DirectedWeightedGraph}s from the skeleton image.
     * Each graph corresponds to one connected component of the graph returned by {@link SkeletonResult#getGraph()}.
     *
     * @return the skeleton graph list
     */
    public List<DirectedWeightedGraph> getGraphs() {
        final List<DirectedWeightedGraph> graphList = new ArrayList<>();
        for (final Graph skelGraph : getSkeletonGraphs()) {
            final DirectedWeightedGraph graph = sntGraphFromSkeletonGraph(skelGraph);
            graph.updateVertexProperties();
            graphList.add(graph);
        }
        return graphList;
    }

    /**
     * Sets the original {@link ImagePlus} to be used during voxel-based loop pruning.
     * See <a href="https://imagej.net/AnalyzeSkeleton.html#Loop_detection_and_pruning">AnalyzeSkeleton documentation</a>
     *
     * @param origIP the original ImagePlus
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setOrigIP(ImagePlus origIP) {
        this.origIP = origIP;
    }

    /**
     * Sets the loop pruning strategy.
     * See <a href="https://imagej.net/AnalyzeSkeleton.html#Loop_detection_and_pruning">AnalyzeSkeleton documentation</a>
     *
     * @param pruneMode the loop prune strategy, e.g., {@link AnalyzeSkeleton_#SHORTEST_BRANCH},
     *                  {@link AnalyzeSkeleton_#LOWEST_INTENSITY_BRANCH} or {@link AnalyzeSkeleton_#LOWEST_INTENSITY_VOXEL}
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setPruneMode(int pruneMode) {
        this.pruneMode = pruneMode;
    }

    /**
     * Sets whether or not to prune branches which end in end-points from the result.
     *
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setPruneEnds(boolean pruneEnds) {
        this.pruneEnds = pruneEnds;
    }

    /**
     * Sets whether or not to calculate the longest shortest-path in the skeleton result.
     *
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setShortestPath(boolean shortestPath) {
        this.shortestPath = shortestPath;
    }

    /**
     * Setting this to false will display both the tagged skeleton image and the shortest path image (if the
     * shortest path calculation is enabled).
     *
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setSilent(boolean silent) {
        this.silent = silent;
    }

    /**
     * @see AnalyzeSkeleton_#run(int, boolean, boolean, ImagePlus, boolean, boolean)
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Sets whether or not to prune branches below a threshold length from the result.
     */
    public void setPruneByLength(boolean pruneByLength) {
        this.pruneByLength = pruneByLength;
    }

    /**
     * The minimum branch length necessary to avoid pruning. This value is only used
     * if {@link SkeletonConverter#pruneByLength} is true.
     *
     * @param lengthThreshold the length threshold
     * @see SkeletonConverter#setPruneByLength(boolean)
     */
    public void setLengthThreshold(double lengthThreshold) {
        if (lengthThreshold < 0) {
            lengthThreshold = 0;
        }
        this.lengthThreshold = lengthThreshold;
    }

    /**
     * Runs AnalyzeSkeleton on the image and gets the Graph Array returned by {@link SkeletonResult#getGraph()}
     */
    private Graph[] getSkeletonGraphs() {
        final AnalyzeSkeleton_ skeleton = new AnalyzeSkeleton_();
        skeleton.setup("", imp);
        SkeletonResult skeletonResult;
        if (pruneByLength) {
            skeletonResult = skeleton.run(pruneMode, lengthThreshold, shortestPath, origIP, silent, verbose);
        } else {
            skeletonResult = skeleton.run(pruneMode, pruneEnds, shortestPath, origIP, silent, verbose);
        }
        return skeletonResult.getGraph();
    }

    /**
     * Convert the AnalyzeSkeleton {@link Graph} object to an SNT {@link Tree}, using a {@link DirectedWeightedGraph}
     * as an intermediary data structure.
     */
    private DirectedWeightedGraph sntGraphFromSkeletonGraph(final Graph skeletonGraph) {
        final DirectedWeightedGraph sntGraph = new DirectedWeightedGraph();
        final Map<Point, SWCPoint> pointMap = new HashMap<>();
        for (final Vertex vertex : skeletonGraph.getVertices()) {
            final Point v = vertex.getPoints().get(0);
            /* Use dummy values for all fields except the point coordinates.
            These will be assigned real values automatically during conversion to Tree. */
            final SWCPoint swcPoint = new SWCPoint(0, 0,
                    v.x * pixelWidth, v.y * pixelHeight, v.z * pixelDepth,
                    0, -1);
            pointMap.put(v, swcPoint);
            sntGraph.addVertex(swcPoint);
        }
        for (final Edge edge : skeletonGraph.getEdges()) {
            final SWCPoint p1 = pointMap.get(edge.getV1().getPoints().get(0));
            final SWCPoint p2 = pointMap.get(edge.getV2().getPoints().get(0));
            final List<Point> slabs = edge.getSlabs();
            if (slabs.isEmpty()) {
                sntGraph.addEdge(p1, p2);
                continue;
            }
            SWCPoint swcSlab = new SWCPoint(0, 0,
                    slabs.get(0).x * pixelWidth, slabs.get(0).y * pixelHeight, slabs.get(0).z * pixelDepth,
                    0, -1);
            pointMap.put(slabs.get(0), swcSlab);
            sntGraph.addVertex(swcSlab);
            for (int i = 1; i < slabs.size(); i++) {
                swcSlab = new SWCPoint(0, 0,
                        slabs.get(i).x * pixelWidth, slabs.get(i).y * pixelHeight, slabs.get(i).z * pixelDepth,
                        0, -1);
                pointMap.put(slabs.get(i), swcSlab);
                sntGraph.addVertex(swcSlab);
                sntGraph.addEdge(pointMap.get(slabs.get(i - 1)), swcSlab);
            }
            sntGraph.addEdge(p1, pointMap.get(slabs.get(0)));
            sntGraph.addEdge(pointMap.get(slabs.get(slabs.size() - 1)), p2);
        }
        convertToDirected(sntGraph);
        return sntGraph;
    }

    /**
     * Enforce consistent edge direction in the resulting {@link DirectedWeightedGraph}, which is required
     * before conversion to a Tree. The graph is traversed depth first starting at some terminal
     * node (i.e, a node of degree 1). The incident edges of each visited node are changed to orient towards
     * the adjacent un-visited node.
     */
    private void convertToDirected(final DirectedWeightedGraph sntGraph) {
        final SWCPoint root = sntGraph.vertexSet().stream()
                .filter(v -> sntGraph.degreeOf(v) == 1).findFirst().orElse(null);
        if (root == null) {
            return;
        }
        sntGraph.setRoot(root);
    }

	/**
	 * Convenience method to skeletonize an 8-bit image using
	 * {@link Skeletonize3D_}.
	 * 
	 * @param imp The 8-bit image to be skeletonized. All non-zero values are
	 *                  considered to be foreground.
	 */
	public static void skeletonize(final ImagePlus imp) {
		final Skeletonize3D_ thin = new Skeletonize3D_();
		thin.setup("", imp);
		thin.run(null);
		imp.updateImage();
	}

    /* IDE debug method */
    public static void main(String[] args) {
        //IJ.open("C:\\Users\\cam\\Desktop\\Drosophila_ddaC_Neuron.tif\\");
        //ImagePlus imp = IJ.getImage();
        ImagePlus imp = new SNTService().demoTrees().get(0).getSkeleton();
        SkeletonConverter converter = new SkeletonConverter(imp, false);
        converter.setPruneEnds(false);
        converter.setPruneMode(AnalyzeSkeleton_.SHORTEST_BRANCH);
        converter.setShortestPath(false);
        converter.setSilent(true);
        converter.setVerbose(true);
        //converter.setPruneByLength(true);
        //converter.setLengthThreshold(200);
        List<Tree> skelTrees = converter.getTrees();
        System.out.println("Num result trees: " + skelTrees.size());
        Viewer3D viewer = new Viewer3D();
        Tree.assignUniqueColors(skelTrees);
        viewer.add(skelTrees);
        viewer.show();
    }

}