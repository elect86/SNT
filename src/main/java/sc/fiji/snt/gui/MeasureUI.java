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

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.Context;
import org.scijava.display.Display;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;

import com.jidesoft.swing.CheckBoxList;
import com.jidesoft.swing.ListSearchable;

import net.imagej.ImageJ;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;

/**
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class MeasureUI extends JFrame {

	private static final long serialVersionUID = 6565638887510865592L;
	private static final String MIN = "Min";
	private static final String MAX = "Max";
	private static final String MEAN = "Mean";
	private static final String STDDEV = "SD";
	private static final String SUM = "Sum";
	private static final String N = "N";
	private static final String[] allFlags = new String[] { MIN, MAX, MEAN, STDDEV, SUM, N };
	private static final Class<?>[] columnClasses = new Class<?>[] { String.class, Boolean.class, Boolean.class,
			Boolean.class, Boolean.class, Boolean.class, Boolean.class };

	@Parameter
	private DisplayService displayService;
	@Parameter
	private PrefService prefService;
	@Parameter
	private SNTService sntService;

	private SNTTable table;
	private boolean distinguishCompartments;
	private boolean saveTable;
	private String lastDirPath;
	private boolean resetTable;

	public MeasureUI(final Collection<Tree> trees) {
		this(SNTUtils.getContext(), trees);
		lastDirPath = System.getProperty("user.home");
	}

	public MeasureUI(final SNT plugin, final Collection<Tree> trees) {
		this(plugin.getContext(), trees);
		lastDirPath = plugin.getPrefs().getRecentDir().getAbsolutePath();
		if (plugin.getUI() != null)
			table = sntService.getTable();
	}

	private MeasureUI(final Context context, final Collection<Tree> trees) {
		super("SNT Measurements");
		context.inject(this);
		final MeasurePanel panel = new MeasurePanel(trees);
		add(panel);
		pack();
		setLocationRelativeTo(null);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				panel.savePreferences();
			}
		});
	}

	class MeasurePanel extends JPanel {

		private static final long serialVersionUID = 1L;
		private final CheckBoxList metricList;
		private final DefaultTableModel statsTableModel;

		@SuppressWarnings("unchecked")
		MeasurePanel(final Collection<Tree> trees) {

			// Stats table
			final JTable statsTable = new JTable(new DefaultTableModel() {

				private static final long serialVersionUID = 1L;

				@Override
				public Class<?> getColumnClass(final int column) {
					return columnClasses[column];
				}

				@Override
				public boolean isCellEditable(final int row, final int column) {
					return column != 0;
				}
			});

			// initialize table mode.
			statsTableModel = (DefaultTableModel) statsTable.getModel();
			statsTableModel.addColumn("Chosen Metric");

			// tweak table
			statsTable.setAutoCreateRowSorter(true);
			statsTable.setComponentPopupMenu(new TablePopupMenu(statsTable));
			statsTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
			for (final String metric : allFlags) {
				statsTableModel.addColumn(metric);
			}
			for (int i = 1; i < statsTable.getColumnCount(); ++i) {
				statsTable.getColumnModel().getColumn(i)
						.setHeaderRenderer(new SelectAllHeader(statsTable, i, statsTable.getColumnName(i)));
			}
			// Enlarge default width of first column. Another option would be to have all
			// columns to auto-fit at all times, e.g., https://stackoverflow.com/a/25570812.
			// Maybe that would be better?
			final String prototypeMetric = "Path mean spine/varicosity density";
			final String prototypeStat = "Mean ";
			for (int i = 0; i < statsTable.getColumnCount(); ++i) {
				final int width = SwingUtilities.computeStringWidth(statsTable.getFontMetrics(statsTable.getFont()),
						(i == 0) ? prototypeMetric : prototypeStat);
				statsTable.getColumnModel().getColumn(i).setMinWidth(width);
			}
			statsTable.setPreferredScrollableViewportSize(statsTable.getPreferredSize());

			// List of choices
			final DefaultListModel<String> listModel = new DefaultListModel<>();
			final List<String> allMetrics = TreeStatistics.getAllMetrics();
			Collections.sort(allMetrics);
			allMetrics.forEach(listModel::addElement);
			metricList = new CheckBoxList(listModel);
			metricList.setClickInCheckBoxOnly(false);
			metricList.setComponentPopupMenu(listPopupMenu());
			metricList.setPrototypeCellValue(prototypeMetric);
			// FIXME: this is slow, but fast enough for a reasonable no. of metrics!?
			metricList.getCheckBoxListSelectionModel().addListSelectionListener(e -> {
				if (!e.getValueIsAdjusting()) {
					final List<Object> selectedMetrics = new ArrayList<>(
							Arrays.asList(metricList.getCheckBoxListSelectedValues()));
					addMetricsToStatsTableModel(selectedMetrics);
				}
			});

			// searchable
			final SNTSearchableBar searchableBar = new SNTSearchableBar(new ListSearchable(metricList),
					"Find... (" + allMetrics.size() + " metrics available)");
			searchableBar.setVisibleButtons(SNTSearchableBar.SHOW_SEARCH_OPTIONS);
			searchableBar.setVisible(true);
			searchableBar.setHighlightAll(true);
			searchableBar.setGuiUtils(new GuiUtils(MeasureUI.this));

			// remember previous state
			loadPreferences();

			// assemble GUI
			setLayout(new GridBagLayout());
			final GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0;
			c.gridy = 0;
			c.weighty = 1.0; // fill height when when resizing pane
			c.fill = GridBagConstraints.BOTH;
			c.weightx = 0.0; // do not fill width when when resizing panel
			c.gridheight = 1;
			add(new JScrollPane(metricList), c);

			c.gridy = 1;
			c.weighty = 0; // do not allow panel to fill height when when resizing pane
			add(searchableBar, c);

			c.gridx = 1;
			c.gridy = 0;
			c.weightx = 1.0; // fill width when when resizing panel
			c.weighty = 1.0; // fill height when when resizing pane
			c.gridwidth = 1;
			add(new JScrollPane(statsTable), c);

			final JButton runButton = new JButton("Measure " + trees.size() + " Reconstruction(s)");
			runButton.addActionListener(new GenerateTableAction(trees, statsTableModel));
			final JPanel buttonPanel = new JPanel();
			buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.LINE_AXIS));
			buttonPanel.add(Box.createHorizontalGlue());
			buttonPanel.add(optionsButton(trees));
			buttonPanel.add(runButton);
			c.gridx = 1;
			c.gridy = 1;
			c.gridwidth = 1;
			c.weighty = 0.0; // do not allow panel to fill height when when resizing pane
			add(buttonPanel, c);
		}

		private JButton optionsButton(final Collection<Tree> trees) {
			final JButton optionsButton = IconFactory.getButton(IconFactory.GLYPH.OPTIONS);
			final JPopupMenu optionsMenu = new JPopupMenu();
			final JCheckBoxMenuItem jcmi1 = new JCheckBoxMenuItem("Distinguish Compartmentments",
					distinguishCompartments);
			jcmi1.addActionListener(e -> distinguishCompartments = jcmi1.isSelected());
			optionsMenu.add(jcmi1);
			optionsMenu.addSeparator();
			final JCheckBoxMenuItem jcmi2 = new JCheckBoxMenuItem("Save Measurements Table", saveTable);
			jcmi2.addActionListener(e -> saveTable = jcmi2.isSelected());
			optionsMenu.add(jcmi2);
			final JCheckBoxMenuItem jcmi3 = new JCheckBoxMenuItem("Reset Measurements Table Before Run", resetTable);
			jcmi3.addActionListener(e -> resetTable = jcmi3.isSelected());
			optionsMenu.add(jcmi3);
			optionsMenu.addSeparator();
			JMenuItem jmi = new JMenuItem("List Cell(s) Being Measured...");
			jmi.addActionListener(e -> showDetails(trees));
			optionsMenu.add(jmi);
			jmi = new JMenuItem("Help...");
			jmi.addActionListener(e -> showHelp());
			optionsMenu.add(jmi);
			optionsButton.addMouseListener(new MouseAdapter() {

				@Override
				public void mousePressed(final MouseEvent e) {
					optionsMenu.show(optionsButton, optionsButton.getWidth() / 2, optionsButton.getHeight() / 2);
				}
			});
			return optionsButton;
		}

		private void showDetails(final Collection<Tree> trees) {
			final StringBuilder sb = new StringBuilder("<HMTL><div align='center'>");
			sb.append("<table><tbody>");
			sb.append("<tr style='border-top:1px solid; border-bottom:1px solid; '>");
			sb.append("<td style='text-align: center;'><b>&nbsp;#&nbsp;</b></td>");
			sb.append("<td style='text-align: center;'><b>Label</b></td>");
			sb.append("<td style='text-align: center;'><b>Spatial Unit</b></td>");
			sb.append("<td style='text-align: center;'><b>Source</b></td>");
			sb.append("</tr>");
			final int[] counter = { 1 };
			trees.forEach(tree -> {
				final Properties props = tree.getProperties();
				sb.append("<tr style='border-bottom:1px solid'>");
				sb.append("<td style='text-align: center;'>").append(counter[0]++).append("</td>");
				sb.append("<td style='text-align: center;'>").append(tree.getLabel()).append("</td>");
				sb.append("<td style='text-align: center;'>").append(props.getOrDefault(Tree.KEY_SPATIAL_UNIT, "N/A")).append("</td>");
				sb.append("<td style='text-align: center;'>").append(props.getOrDefault(Tree.KEY_SOURCE, "N/A")).append("</td>");
				sb.append("</tr>");
			});
			sb.append("</tbody></table>");
			GuiUtils.showHTMLDialog(sb.toString(), "" + trees.size() + " Tree(s) Being Measured");
		}

		private void showHelp() {
//			GuiUtils.showHTMLDialog("<html>Reconstructions being analyzed: "//
//			+ "<p>TBD"//
//			//trees.iterator().next().getProperties().get(Tree.)
//			+ "</html>", "Measurements");
		}

		private void loadPreferences() {
			distinguishCompartments = prefService.getBoolean(getClass(), "distinguish", false);
			resetTable = prefService.getBoolean(getClass(), "resettable", true);
			saveTable = prefService.getBoolean(getClass(), "savetable", false);
			lastDirPath = prefService.get(getClass(), "lastdir", lastDirPath);
			final List<String> metrics = prefService.getList(getClass(), "choices");
			metricList.addCheckBoxListSelectedValues(metrics.toArray(new String[0]));
			addMetricsToStatsTableModel(metrics);
		}

		private void savePreferences() {
			final List<String> metrics = new ArrayList<>();
			for (final Object choice : metricList.getCheckBoxListSelectedValues())
				metrics.add((String) choice);
			prefService.put(getClass(), "choices", metrics);
			prefService.put(getClass(), "distinguish", distinguishCompartments);
			prefService.put(getClass(), "savetable", saveTable);
			prefService.put(getClass(), "resettable", resetTable);
			prefService.put(getClass(), "lastdir", lastDirPath);
		}

		private void addMetricsToStatsTableModel(final List<?> metrics) {
			final List<Integer> metricIndicesToRemove = new ArrayList<>();
			final List<Object> existingMetrics = new ArrayList<>();
			for (int i = 0; i < statsTableModel.getRowCount(); ++i) {
				final Object existingMetric = statsTableModel.getValueAt(i, 0);
				if (!metrics.contains(existingMetric)) {
					metricIndicesToRemove.add(i);
				} else {
					existingMetrics.add(existingMetric);
				}
			}
			removeRows(statsTableModel, metricIndicesToRemove);
			metrics.removeAll(existingMetrics);
			for (final Object metric : metrics)
				statsTableModel.addRow(new Object[] { metric, false, false, false, false, false });
		}

		private void removeRows(final DefaultTableModel model, final List<Integer> indices) {
			Collections.sort(indices);
			for (int i = indices.size() - 1; i >= 0; i--) {
				model.removeRow(indices.get(i));
			}
		}

		private JPopupMenu listPopupMenu() {
			final JPopupMenu pMenu = new JPopupMenu();
			JMenuItem mi = new JMenuItem("Select Highlighted");
			mi.addActionListener(e -> setHighlightedSelected(true));
			pMenu.add(mi);
			mi = new JMenuItem("Deselect Highlighted");
			mi.addActionListener(e -> setHighlightedSelected(false));
			pMenu.add(mi);
			pMenu.addSeparator();
			mi = new JMenuItem("Select All");
			mi.addActionListener(e -> metricList.selectAll());
			pMenu.add(mi);
			mi = new JMenuItem("Select None");
			mi.addActionListener(e -> metricList.clearCheckBoxListSelection());
			pMenu.add(mi);
			return pMenu;
		}

		private void setHighlightedSelected(final boolean select) {
			final int[] indices = metricList.getSelectedIndices();
			metricList.setValueIsAdjusting(true);
			for (int i = 0; i < indices.length; i++) {
				if (select)
					metricList.addCheckBoxListSelectedIndex(indices[i]);
				else
					metricList.removeCheckBoxListSelectedIndex(indices[i]);
			}
			metricList.setValueIsAdjusting(false);
		}

	}

	class GenerateTableAction extends AbstractAction {

		private static final long serialVersionUID = 1L;
		final Collection<Tree> trees;
		final DefaultTableModel tableModel;
		final GuiUtils guiUtils;

		GenerateTableAction(final Collection<Tree> trees, final DefaultTableModel tableModel) {
			super("Run", null);
			this.trees = trees;
			this.tableModel = tableModel;
			guiUtils = new GuiUtils(MeasureUI.this);
		}

		@Override
		public void actionPerformed(final ActionEvent e) {
			if (tableModel.getRowCount() == 0) {
				guiUtils.error("You must select at least one metric.");
				return;
			}
			if (!atLeastOneStatChosen() && trees.size() > 1) {
				guiUtils.error("You must select at least one statistic.");
				return;
			}
			final SNTTable table = (MeasureUI.this.table == null) ? new SNTTable() : MeasureUI.this.table;
			if (resetTable) table.clear();
			for (final Tree tree : trees) {
				final Set<Integer> compartments = tree.getSWCTypes(false);
				if (distinguishCompartments && compartments.size() > 1) {
					for (final int type : compartments)
						measureTree(tree.subTree(type), table);
				} else {
					measureTree(tree, table);
					table.appendToLastRow("No. of compartments", compartments.size());
				}
			}
			if (table.isEmpty() || (!distinguishCompartments && table.getColumnCount() < 2)) {
				guiUtils.error("Measurements table is empty. Please make sure your choices are valid.");
				return;
			}
			final Display<?> display = displayService.getDisplay("SNT Measurements");
			if (display != null && resetTable) display.close();
			displayService.createDisplay("SNT Measurements", table);

			if (saveTable) {
				final File dir = (lastDirPath == null) ? new File(System.getProperty("user.home"))
						: new File(lastDirPath);
				final File out = guiUtils.saveFile("Save Table", new File(dir, "SNT_Measurements.csv"),
						Arrays.asList(".csv"));
				if (out == null)
					return;
				try {
					table.save(out);
				} catch (final IOException e1) {
					guiUtils.error(e1.getMessage());
					e1.printStackTrace();
				}
				lastDirPath = out.getParent();
			}
		}

		private boolean atLeastOneStatChosen() {
			for (int i = 0; i < tableModel.getRowCount(); ++i) {
				for (int j = 1; j < tableModel.getColumnCount(); ++j) {
					final Object cell = tableModel.getValueAt(i, j);
					if (cell != null && (boolean) cell)
						return true;
				}
			}
			return false;
		}

		private boolean atLeastOneStatChosen(final int row) {
			for (int j = 1; j < tableModel.getColumnCount(); ++j) {
				final Object cell = tableModel.getValueAt(row, j);
				if (cell != null && (boolean) cell)
					return true;
			}
			return false;
		}

		private void measureTree(final Tree tree, final SNTTable table) {
			table.insertRow(tree.getLabel());
			final TreeStatistics tStats = new TreeStatistics(tree);
			TreeStatistics.setExactMetricMatch(true);
			for (int i = 0; i < tableModel.getRowCount(); ++i) {
				final String metric = (String) tableModel.getValueAt(i, 0);
				if (!atLeastOneStatChosen(i))
					continue;
				final SummaryStatistics summaryStatistics = tStats.getSummaryStats(metric);
				if (summaryStatistics.getN() == 1) {
					table.appendToLastRow(metric + " (Single value metric)", summaryStatistics.getSum());
					continue;
				}
				for (int j = 1; j < tableModel.getColumnCount(); ++j) {
					final Object cell = tableModel.getValueAt(i, j);
					if (cell == null || !(boolean) cell)
						continue;
					final String measurement = tableModel.getColumnName(j);
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
					case N:
						value = summaryStatistics.getN();
						break;
					default:
						throw new IllegalArgumentException("[BUG] Unknown statistic: " + measurement);
					}
					table.appendToLastRow(metric + " (" + measurement + ")", value);
				}
			}
		}

	}

	/**
	 * A TableCellRenderer that selects all or none of a Boolean column.
	 * <p>
	 * Adapted from https://stackoverflow.com/a/7137801
	 */
	static class SelectAllHeader extends JToggleButton implements TableCellRenderer {

		private static final long serialVersionUID = 1L;
		private static final String ALL_SELECTED = "✓ ";
		private final String label;
		private final JTable table;
		private final TableModel tableModel;
		private final JTableHeader header;
		private final TableColumnModel tcm;
		private final int targetColumn;
		private int viewColumn;

		public SelectAllHeader(final JTable table, final int targetColumn, final String label) {
			super(label);
			this.label = label;
			this.table = table;
			this.tableModel = table.getModel();
			if (tableModel.getColumnClass(targetColumn) != Boolean.class) {
				throw new IllegalArgumentException("Boolean column required.");
			}
			this.targetColumn = targetColumn;
			this.header = table.getTableHeader();
			this.tcm = table.getColumnModel();
			this.applyUI();
			this.addItemListener(new ItemHandler());
			header.addMouseListener(new MouseHandler());

			// FIXME: This does not appear to work when the table
			// has multiple of these listeners. Multiple columns get
			// selected.

			// tableModel.addTableModelListener(new ModelHandler());
		}

		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
				final boolean hasFocus, final int row, final int column) {
			return this;
		}

		private class ItemHandler implements ItemListener {

			@Override
			public void itemStateChanged(final ItemEvent e) {
				final boolean state = e.getStateChange() == ItemEvent.SELECTED;
				setText((state) ? ALL_SELECTED + label : label);
				for (int r = 0; r < table.getRowCount(); r++) {
					table.setValueAt(state, r, viewColumn);
				}
			}
		}

		@Override
		public void updateUI() {
			super.updateUI();
			applyUI();
		}

		private void applyUI() {
			this.setFont(UIManager.getFont("TableHeader.font"));
			this.setBorder(UIManager.getBorder("TableHeader.cellBorder"));
			this.setBackground(UIManager.getColor("TableHeader.background"));
			this.setForeground(UIManager.getColor("TableHeader.foreground"));
		}

		private class MouseHandler extends MouseAdapter {

			@Override
			public void mouseClicked(final MouseEvent e) {
				viewColumn = header.columnAtPoint(e.getPoint());
				final int modelColumn = tcm.getColumn(viewColumn).getModelIndex();
				if (modelColumn == targetColumn) {
					doClick();
				}
			}
		}
	}

	/* see https://stackoverflow.com/questions/16743427/ */
	static class TablePopupMenu extends JPopupMenu {
		private static final long serialVersionUID = 1L;
		private int rowAtClickPoint;
		private int columnAtClickPoint;
		private final JTable table;

		public TablePopupMenu(final JTable table) {
			super();
			this.table = table;
			addPopupMenuListener(new PopupMenuListener() {

				@Override
				public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
					SwingUtilities.invokeLater(() -> {
						final Point clickPoint = SwingUtilities.convertPoint(TablePopupMenu.this, new Point(0, 0),
								table);
						rowAtClickPoint = table.rowAtPoint(clickPoint);
						columnAtClickPoint = table.columnAtPoint(clickPoint);
						for (final MenuElement element : getSubElements()) {
							if (!(element instanceof JMenuItem))
								continue;
							if (((JMenuItem) element).getText().endsWith("Column")) {
								((JMenuItem) element).setEnabled(columnAtClickPoint > 0);
							}
							if (((JMenuItem) element).getText().endsWith("Row(s)")) {
								((JMenuItem) element).setEnabled(table.getSelectedRowCount() > 0);
							}
						}
					});
				}

				@Override
				public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {
				}

				@Override
				public void popupMenuCanceled(final PopupMenuEvent e) {
				}

			});
			JMenuItem mi = new JMenuItem("Select All");
			mi.addActionListener(e -> setAllState(true));
			add(mi);
			mi = new JMenuItem("Select This Column");
			mi.addActionListener(e -> setColumnState(true));
			add(mi);
			mi = new JMenuItem("Select Highlighted Row(s)");
			mi.addActionListener(e -> setSelectedRowsState(true));
			add(mi);
			addSeparator();
			mi = new JMenuItem("Select None");
			mi.addActionListener(e -> setAllState(false));
			add(mi);
			mi = new JMenuItem("Deselect This Column");
			mi.addActionListener(e -> setColumnState(false));
			add(mi);
			mi = new JMenuItem("Deselect Highlighted Row(s)");
			mi.addActionListener(e -> setSelectedRowsState(false));
			add(mi);
		}

		private void setColumnState(final boolean state) {
			if (columnAtClickPoint == 0)
				// This is the metric String column, we don't want to change this
				return;
			for (int i = 0; i < table.getRowCount(); i++) {
				table.setValueAt(state, i, columnAtClickPoint);
			}
		}

		@SuppressWarnings("unused")
		private void setRowState(final boolean state) {
			// Boolean columns start at idx == 1
			for (int i = 1; i < table.getColumnCount(); i++) {
				table.setValueAt(state, rowAtClickPoint, i);
			}
		}

		private void setAllState(final boolean state) {
			for (int row = 0; row < table.getRowCount(); row++) {
				for (int col = 1; col < table.getColumnCount(); col++) { // Skip metric String column
					table.setValueAt(state, row, col);
				}
			}
		}

		private void setSelectedRowsState(final boolean state) {
			final int[] selectedIndices = table.getSelectedRows();
			for (int idx = 0; idx < selectedIndices.length; idx++) {
				for (int col = 1; col < table.getColumnCount(); col++) { // Skip metric String column
					table.setValueAt(state, selectedIndices[idx], col);
				}
			}
		}

	}

	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		GuiUtils.setLookAndFeel();
		final SNTService sntService = ij.get(SNTService.class);
//		final MeasureUI frame = new MeasureUI(Collections.singleton(sntService.demoTree()));
		final MeasureUI frame = new MeasureUI(sntService.demoTrees());
		SwingUtilities.invokeLater(() -> frame.setVisible(true));
	}

}