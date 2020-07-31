package sc.fiji.snt.analysis.graph;

import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.scijava.util.ColorRGB;

import java.util.HashMap;
import java.util.Map;

public class SNTGraph<V, DefaultWeightedEdge> extends DefaultDirectedWeightedGraph<V, DefaultWeightedEdge> {

	private static final long serialVersionUID = 8458292348918037500L;

	private final Map<V, ColorRGB> vertexColorRGBMap;
    private final Map<DefaultWeightedEdge, ColorRGB> edgeColorRGBMap;

    public SNTGraph(Class<? extends DefaultWeightedEdge> edgeClass) {
        super(edgeClass);
        vertexColorRGBMap = new HashMap<>();
        edgeColorRGBMap = new HashMap<>();
    }

    public void setVertexColor(V vertex, ColorRGB color) {
        if (containsVertex(vertex)) {
            vertexColorRGBMap.put(vertex, color);
        }
    }

    public void setEdgeColor(DefaultWeightedEdge edge, ColorRGB color) {
        if (containsEdge(edge)) {
            edgeColorRGBMap.put(edge, color);
        }
    }

    public ColorRGB getVertexColor(V vertex) {
        if (containsVertex(vertex) && vertexColorRGBMap.containsKey(vertex)) {
            return vertexColorRGBMap.get(vertex);
        }
        return null;
    }

    public ColorRGB getEdgeColor(DefaultWeightedEdge edge) {
        if (containsEdge(edge) && edgeColorRGBMap.containsKey(edge)) {
            return edgeColorRGBMap.get(edge);
        }
        return null;
    }

    public Map<V, ColorRGB> getVertexColorRGBMap() {
        return vertexColorRGBMap;
    }

    public Map<DefaultWeightedEdge, ColorRGB> getEdgeColorRGBMap() {
        return edgeColorRGBMap;
    }

}
