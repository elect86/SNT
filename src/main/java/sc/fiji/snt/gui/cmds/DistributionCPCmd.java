/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.imagej.ImageJ;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.module.MutableModuleItem;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;

import sc.fiji.snt.analysis.MultiTreeStatistics;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Command for plotting distributions of whole-cell morphometric properties of
 * {@link Tree}s
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false,
	label = "Distribution Analysis (Groups)", initializer = "init")
public class DistributionCPCmd extends CommonDynamicCmd {

	@Parameter
	private PrefService prefService;

	@Parameter(required = true, label = "Measurement")
	private String measurementChoice;

	@Parameter(required = true, label = "Compartment", choices= {"All", "Axon", "Dendrites"})
	private String compartment;

	@Parameter(required = true)
	private Collection<Tree> trees;

	@Parameter(required = false, visibility = ItemVisibility.INVISIBLE)
	private boolean calledFromPathManagerUI;

	protected void init() {
		super.init(false);
		if (trees == null || trees.isEmpty()) {
			cancel("Collection of Trees required but none found.");
		}
		final MutableModuleItem<String> measurementChoiceInput = getInfo()
			.getMutableInput("measurementChoice", String.class);
		final List<String> choices = MultiTreeStatistics.getMetrics();
		if (!calledFromPathManagerUI) choices.remove(MultiTreeStatistics.VALUES);
		Collections.sort(choices);
		measurementChoiceInput.setChoices(choices);
		measurementChoiceInput.setValue(this, prefService.get(getClass(),
			"measurementChoice", MultiTreeStatistics.LENGTH));
		resolveInput("setValuesFromSNTService");
	}

	@Override
	public void run() {
		final String scope = ("All".equals(compartment)) ? "AxDe" : compartment;
		String failures = "";
		try {
			if (scope.contains("De")) {
				final MultiTreeStatistics dStats = new MultiTreeStatistics(trees, "dendrites");
				dStats.setLabel("Dendrites");
				dStats.getHistogram(measurementChoice).setVisible(true);
			}
		} catch (final java.util.NoSuchElementException ignored) {
			failures += "dendritic";
		}
		try {
			if (scope.contains("Ax")) {
				final MultiTreeStatistics aStats = new MultiTreeStatistics(trees, "axon");
				aStats.setLabel("Axons");
				aStats.getHistogram(measurementChoice).setVisible(true);
			}
		} catch (final java.util.NoSuchElementException ignored) {
			failures += (failures.isEmpty()) ? "axonal" : " or axonal";
		}
		if (!failures.isEmpty()) {
			msg("It was not possible to access data for " + failures + "compartment(s).", "Error");
		}
		resetUI();
	}

	/* IDE debug method **/
	public static void main2(final String[] args) {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		final Map<String, Object> input = new HashMap<>();
		input.put("trees", new SNTService().demoTrees());
		ij.command().run(DistributionCPCmd.class, true, input);
	}

	/* IDE debug method */
	public static void main(final String[] args) {
		final List<Tree> treeCollection = new SNTService().demoTrees();
		final MultiTreeStatistics dStats = new MultiTreeStatistics(treeCollection, "dendrites");
		dStats.setLabel("Dendrites");
		dStats.getHistogram("Length").setVisible(true);
	}

}