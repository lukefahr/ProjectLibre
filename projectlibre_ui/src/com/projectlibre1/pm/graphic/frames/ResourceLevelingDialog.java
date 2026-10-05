/*******************************************************************************
 * The contents of this file are subject to the Common Public Attribution License 
 * Version 1.0 (the "License"); you may not use this file except in compliance with 
 * the License. You may obtain a copy of the License at 
 * http://www.projectlibre.com/license . The License is based on the Mozilla Public 
 * License Version 1.1 but Sections 14 and 15 have been added to cover use of 
 * software over a computer network and provide for limited attribution for the 
 * Original Developer. In addition, Exhibit A has been modified to be consistent 
 * with Exhibit B. 
 *
 * Software distributed under the License is distributed on an "AS IS" basis, 
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for the 
 * specific language governing rights and limitations under the License. The 
 * Original Code is ProjectLibre. The Original Developer is the Initial Developer 
 * and is ProjectLibre Inc. All portions of the code written by ProjectLibre are 
 * Copyright (c) 2012-2019. All Rights Reserved. Contributor ProjectLibre, Inc.
 *******************************************************************************/
package com.projectlibre1.pm.graphic.frames;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import com.projectlibre1.pm.calendar.WorkCalendar;
import com.projectlibre1.pm.resource.Resource;
import com.projectlibre1.pm.resource.ResourceImpl;
import com.projectlibre1.pm.scheduling.SerialResourceLeveler;
import com.projectlibre1.pm.task.Project;
import com.projectlibre1.pm.task.Task;
import com.projectlibre1.strings.Messages;

/**
 * Interactive resource leveling: pick the resources to level, run, look at what moved
 * (the Gantt behind the dialog updates as well), run again for other resources, and
 * undo everything done since the dialog opened if the result is not wanted.
 */
public class ResourceLevelingDialog extends JDialog {
	private static final long serialVersionUID = 1L;
	private static ResourceLevelingDialog current = null;

	private final Project project;
	private final SerialResourceLeveler leveler;
	private final Map<Task, Long> delaysAtOpen;
	private final List<Resource> resources = new ArrayList<Resource>();
	private final JList resourceList;
	private final JCheckBox withinSlack;
	private final JLabel summary = new JLabel(" ");
	private final DefaultTableModel moves;
	private final JTextArea remaining = new JTextArea(4, 40);
	private final DateFormat dateFormat = com.projectlibre1.options.EditOption.getInstance().getDateFormat();

	public static void open(Frame owner, Project project, Collection preselected) {
		if (current != null && current.isVisible() && current.project == project) {
			current.preselect(preselected);
			current.toFront();
			return;
		}
		if (current != null)
			current.dispose();
		current = new ResourceLevelingDialog(owner, project, preselected);
		current.setLocationRelativeTo(owner);
		current.setVisible(true);
	}

	private ResourceLevelingDialog(Frame owner, Project project, Collection preselected) {
		super(owner, Messages.getString("Leveling.title"), false); //$NON-NLS-1$
		this.project = project;
		leveler = new SerialResourceLeveler(project);
		delaysAtOpen = leveler.captureDelays();

		DefaultListModel listModel = new DefaultListModel();
		for (Iterator i = project.getResourcePool().getResourceList().iterator(); i.hasNext();) {
			Object r = i.next();
			if (r instanceof ResourceImpl && ((ResourceImpl) r).isLabor() && !((ResourceImpl) r).isDefault()) {
				resources.add((Resource) r);
				listModel.addElement(((Resource) r).getName());
			}
		}
		resourceList = new JList(listModel);
		resourceList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		resourceList.setVisibleRowCount(14);
		preselect(preselected);

		JButton all = new JButton(Messages.getString("Leveling.all")); //$NON-NLS-1$
		all.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				resourceList.setSelectionInterval(0, resources.size() - 1);
			}
		});
		JButton none = new JButton(Messages.getString("Leveling.none")); //$NON-NLS-1$
		none.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				resourceList.clearSelection();
			}
		});
		withinSlack = new JCheckBox(Messages.getString("Leveling.withinSlack")); //$NON-NLS-1$

		JPanel left = new JPanel(new BorderLayout(4, 4));
		left.setBorder(BorderFactory.createTitledBorder(Messages.getString("Leveling.resources"))); //$NON-NLS-1$
		left.add(new JScrollPane(resourceList), BorderLayout.CENTER);
		JPanel selectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		selectButtons.add(all);
		selectButtons.add(none);
		JPanel leftBottom = new JPanel();
		leftBottom.setLayout(new BoxLayout(leftBottom, BoxLayout.Y_AXIS));
		leftBottom.add(selectButtons);
		leftBottom.add(withinSlack);
		left.add(leftBottom, BorderLayout.SOUTH);

		moves = new DefaultTableModel(new Object[] {
				Messages.getString("Leveling.colId"), Messages.getString("Leveling.colTask"), //$NON-NLS-1$ //$NON-NLS-2$
				Messages.getString("Leveling.colOldStart"), Messages.getString("Leveling.colNewStart"), //$NON-NLS-1$ //$NON-NLS-2$
				Messages.getString("Leveling.colDelay") }, 0) { //$NON-NLS-1$
			private static final long serialVersionUID = 1L;
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		JTable movesTable = new JTable(moves);
		movesTable.getColumnModel().getColumn(0).setMaxWidth(50);
		movesTable.getColumnModel().getColumn(1).setPreferredWidth(220);
		movesTable.getColumnModel().getColumn(2).setPreferredWidth(140);
		movesTable.getColumnModel().getColumn(3).setPreferredWidth(140);
		movesTable.getColumnModel().getColumn(4).setMaxWidth(80);
		remaining.setEditable(false);
		remaining.setLineWrap(false);

		JPanel right = new JPanel(new BorderLayout(4, 4));
		right.setBorder(BorderFactory.createTitledBorder(Messages.getString("Leveling.result"))); //$NON-NLS-1$
		right.add(summary, BorderLayout.NORTH);
		JSplitPane results = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(movesTable), new JScrollPane(remaining));
		results.setResizeWeight(0.7);
		results.setContinuousLayout(true);
		right.add(results, BorderLayout.CENTER);

		JButton level = new JButton(Messages.getString("Leveling.level")); //$NON-NLS-1$
		level.setName("level"); //$NON-NLS-1$
		level.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				level();
			}
		});
		JButton clear = new JButton(Messages.getString("Leveling.clear")); //$NON-NLS-1$
		clear.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				clear();
			}
		});
		JButton undo = new JButton(Messages.getString("Leveling.undoAll")); //$NON-NLS-1$
		undo.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				undoAll();
			}
		});
		JButton close = new JButton(Messages.getString("Leveling.close")); //$NON-NLS-1$
		close.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				dispose();
			}
		});
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
		buttons.add(level);
		buttons.add(clear);
		buttons.add(Box.createHorizontalStrut(16));
		buttons.add(undo);
		buttons.add(close);
		getRootPane().setDefaultButton(level);

		JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
		split.setContinuousLayout(true);
		split.setResizeWeight(0.25);
		getContentPane().setLayout(new BorderLayout(4, 4));
		getContentPane().add(split, BorderLayout.CENTER);
		getContentPane().add(buttons, BorderLayout.SOUTH);
		setPreferredSize(new Dimension(900, 480));
		pack();
	}

	private void preselect(Collection preselected) {
		resourceList.clearSelection();
		boolean any = false;
		if (preselected != null) {
			for (Iterator i = preselected.iterator(); i.hasNext();) {
				int index = resources.indexOf(i.next());
				if (index >= 0) {
					resourceList.addSelectionInterval(index, index);
					any = true;
				}
			}
		}
		if (!any && !resources.isEmpty())
			resourceList.setSelectionInterval(0, resources.size() - 1);
	}

	private List<Resource> selectedResources() {
		List<Resource> selected = new ArrayList<Resource>();
		int[] indices = resourceList.getSelectedIndices();
		for (int i = 0; i < indices.length; i++)
			selected.add(resources.get(indices[i]));
		return selected;
	}

	private void configure() {
		List<Resource> selected = selectedResources();
		leveler.setScope(selected.size() == resources.size() ? null : selected);
		leveler.setWithinSlackOnly(withinSlack.isSelected());
	}

	private void level() {
		if (resourceList.getSelectedIndices().length == 0) {
			summary.setText(Messages.getString("Leveling.selectResources")); //$NON-NLS-1$
			return;
		}
		configure();
		SerialResourceLeveler.Result result = leveler.level();
		project.setDirty(true);
		moves.setRowCount(0);
		for (SerialResourceLeveler.Move move : result.moves) {
			moves.addRow(new Object[] { Long.valueOf(move.task.getId()), move.task.getName(),
					dateFormat.format(new Date(move.oldStart)), dateFormat.format(new Date(move.newStart)),
					hours(move.delay) + (move.resolved ? "" : " *") }); //$NON-NLS-1$ //$NON-NLS-2$
		}
		StringBuffer text = new StringBuffer();
		text.append(result.delayed).append(' ').append(Messages.getString("Leveling.tasksDelayed")); //$NON-NLS-1$
		text.append(", ").append(result.overloadedDaysBefore).append(" -> ").append(result.overloadedDaysAfter) //$NON-NLS-1$ //$NON-NLS-2$
			.append(' ').append(Messages.getString("Leveling.overloadedDays")); //$NON-NLS-1$
		if (result.unresolved > 0)
			text.append(", ").append(result.unresolved).append(' ').append(Messages.getString("Leveling.unresolved")); //$NON-NLS-1$ //$NON-NLS-2$
		summary.setText(text.toString());
		StringBuffer rest = new StringBuffer();
		if (!result.remaining.isEmpty()) {
			rest.append(Messages.getString("Leveling.remaining")); //$NON-NLS-1$
			for (String line : result.remaining)
				rest.append('\n').append(line);
		}
		remaining.setText(rest.toString());
		remaining.setCaretPosition(0);
	}

	private void clear() {
		configure();
		int cleared = leveler.clearLevelingDelays();
		if (cleared > 0)
			project.setDirty(true);
		moves.setRowCount(0);
		remaining.setText(""); //$NON-NLS-1$
		summary.setText(cleared + " " + Messages.getString("Leveling.cleared")); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private void undoAll() {
		int changed = leveler.restoreDelays(delaysAtOpen);
		if (changed > 0)
			project.setDirty(true);
		moves.setRowCount(0);
		remaining.setText(""); //$NON-NLS-1$
		summary.setText(Messages.getString("Leveling.undone")); //$NON-NLS-1$
	}

	private static String hours(long millis) {
		return (Math.round(millis * 10.0 / WorkCalendar.MILLIS_IN_HOUR) / 10.0) + "h"; //$NON-NLS-1$
	}
}
