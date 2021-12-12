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

package sc.fiji.snt.gui;

import net.imagej.ImageJ;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;

import com.jidesoft.swing.CheckBoxList;

import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Collection;

/**
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class MeasureUI extends JFrame {

    @Parameter
    DisplayService displayService;

    static final String MIN = "Min";
    static final String MAX = "Max";
    static final String MEAN = "Mean";
    static final String STDDEV = "SD";
    static final String SUM = "Sum";

    static final String[] allFlags = new String[]{MIN, MAX, MEAN, STDDEV, SUM};
    static final Class[] columnClasses = new Class[]{String.class, Boolean.class, Boolean.class, Boolean.class,
            Boolean.class, Boolean.class};

    final Collection<Tree> trees;

    public MeasureUI(SNT plugin, Collection<Tree> trees) {
        super("SNT Measurements");
        plugin.getContext().inject(this);
        this.trees = trees;
        MeasurePanel panel = new MeasurePanel(trees, displayService);
        add(panel);
        pack();
        setLocationRelativeTo(null);
    }

    static class MeasurePanel extends JPanel {

        private final CheckBoxList metricList;
        private final DefaultListModel<String> listModel;

        MeasurePanel(Collection<Tree> trees, DisplayService displayService) {
            setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();

            c.gridx = 0;
            c.gridy = 0;
            c.fill = GridBagConstraints.BOTH;
            listModel = new DefaultListModel<>();
            TreeStatistics.getAllMetrics().forEach(listModel::addElement);
            metricList = new CheckBoxList(listModel);
            metricList.setClickInCheckBoxOnly(false);
            metricList.setComponentPopupMenu(listPopupMenu());
            JScrollPane metricListScrollPane = new JScrollPane(metricList);
            add(metricListScrollPane, c);

            c.gridx = 1;
            c.gridy = 0;
            c.fill = GridBagConstraints.BOTH;
            JTable statsTable = new JTable(new DefaultTableModel()) {

                private static final long serialVersionUID = 1L;

                @Override
                public Class getColumnClass(int column) {
                    return columnClasses[column];
                }
            };
            DefaultTableModel tableModel = (DefaultTableModel) statsTable.getModel();
            tableModel.addColumn("Metric");
            for (String metric : allFlags) {
                tableModel.addColumn(metric);
            }
            metricList.getCheckBoxListSelectionModel().addListSelectionListener(e -> {
      			if (!e.getValueIsAdjusting()) {
      				final Object[] metrics = metricList.getCheckBoxListSelectedValues();
      				tableModel.setRowCount(0);
      				for (final Object metric : metrics)
      					tableModel.addRow(new Object[]{metric.toString(), false, false, false, false, false});
      			}
      		});

            JScrollPane statsTableScrollPane = new JScrollPane(statsTable);
            add(statsTableScrollPane, c);

            c.gridx = 0;
            c.gridy = 1;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.CENTER;
            JPanel buttonPanel = new JPanel();

            JButton runButton = new JButton("Run");
            runButton.addActionListener(new GenerateTableAction(trees, tableModel, displayService));
            buttonPanel.add(runButton);

            add(buttonPanel, c);
        }

		private JPopupMenu listPopupMenu() {
			final JPopupMenu pMenu = new JPopupMenu();
			JMenuItem mi = new JMenuItem("Clear Selection");
			mi.addActionListener(e -> metricList.clearCheckBoxListSelection());
			pMenu.add(mi);
			mi = new JMenuItem("Select All");
			mi.addActionListener(e -> metricList.selectAll());
			pMenu.add(mi);
			return pMenu;
		}

    }

    static class GenerateTableAction extends AbstractAction {

        private Collection<Tree> trees;
        private DefaultTableModel tableModel;
        private DisplayService displayService;

        GenerateTableAction(Collection<Tree> trees, DefaultTableModel tableModel, DisplayService displayService) {
            super("Run", null);
            this.trees = trees;
            this.tableModel = tableModel;
            this.displayService = displayService;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            SNTTable table = new SNTTable();
            for (Tree tree : trees) {
                table.insertRow(tree.getLabel());
                TreeStatistics tStats = new TreeStatistics(tree);
                for (int i = 0; i < tableModel.getRowCount(); ++i) {
                    String metric = (String) tableModel.getValueAt(i, 0);
                    SummaryStatistics summaryStatistics = tStats.getSummaryStats(metric);
                    for (int j = 1; j < tableModel.getColumnCount(); ++j) {
                        if (!(boolean)tableModel.getValueAt(i, j))
                            continue;
                        String measurement = tableModel.getColumnName(j);
                        final double value;
                        switch (measurement) {
                            case MIN:
                                value = summaryStatistics.getMin();
                                break;
                            case MAX:
                                value = summaryStatistics.getMax();
                                break;
                            case MEAN:
                                value = summaryStatistics.getMean();
                                break;
                            case STDDEV:
                                value = summaryStatistics.getStandardDeviation();
                                break;
                            case SUM:
                                value = summaryStatistics.getSum();
                                break;
                            default:
                                throw new IllegalArgumentException("Unknown statistic: " + measurement);
                        }
                        table.appendToLastRow(metric + "(" + measurement + ")", value);
                    }
                }
            }

            if (!table.isEmpty()) {
                displayService.createDisplay("SNT Measurements", table);
            }
        }
    }

    public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService sntService = ij.get(SNTService.class);
        SNT plugin = sntService.initialize(true);
        MeasureUI frame = new MeasureUI(plugin, sntService.demoTrees());
        //Schedule a job for the event-dispatching thread:
        //creating and showing this application's GUI.
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                frame.setVisible(true);
            }
        });
    }

}