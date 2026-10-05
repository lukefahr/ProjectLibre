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
 *
 * Alternatively, the contents of this file may be used under the terms of the 
 * ProjectLibre End-User License Agreement (the ProjectLibre License) in which case 
 * the provisions of the ProjectLibre License are applicable instead of those above. 
 *******************************************************************************/
package com.projectlibre1.pm.scheduling;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.projectlibre1.field.FieldContext;
import com.projectlibre1.pm.assignment.Assignment;
import com.projectlibre1.pm.calendar.WorkCalendar;
import com.projectlibre1.pm.dependency.Dependency;
import com.projectlibre1.pm.dependency.HasDependencies;
import com.projectlibre1.pm.resource.Resource;
import com.projectlibre1.pm.task.NormalTask;
import com.projectlibre1.pm.task.Project;
import com.projectlibre1.pm.task.Task;
import com.projectlibre1.pm.time.MutableInterval;

/**
 * Serial resource leveler.
 *
 * Leaf tasks with labor assignments are placed one at a time. The next task to place is
 * always one whose predecessors have all been placed, choosing the highest priority
 * first, then the earliest current start, then the lowest id. A task is accepted where
 * it stands if, for every day it covers, each of its resources has enough capacity left
 * after the tasks already placed; otherwise its leveling delay is increased so that it
 * starts after the first overloaded day, the schedule is recalculated and the check
 * repeats. Tasks that have started, are external, or carry priority 1000 are never
 * moved but still count toward the load of their resources.
 *
 * Existing leveling delays are discarded first, so running the leveler twice gives the
 * same answer, and {@link #clearLevelingDelays(Project)} undoes everything it did.
 */
public class SerialResourceLeveler {
	/** As in MS Project, this priority means "do not level". */
	public static final int DO_NOT_LEVEL_PRIORITY = 1000;
	private static final int DEFAULT_PRIORITY = 500;
	private static final long TOLERANCE = WorkCalendar.MILLIS_IN_MINUTE;
	private static final int MAX_MOVES_PER_TASK = 400;

	public static class Result {
		public int candidates;
		public int fixed;
		public int delayed;
		public int unresolved;
		public long totalDelay;
		public int overloadedDaysBefore;
		public int overloadedDaysAfter;
		/** One line per delayed task. */
		public final List<String> details = new ArrayList<String>();
		/** One line per resource-day still over-allocated afterwards, naming the tasks involved. */
		public final List<String> remaining = new ArrayList<String>();
		public String toString() {
			return "leveled " + candidates + " tasks (" + fixed + " held in place): " + delayed + " delayed, "
				+ unresolved + " could not be resolved, overloaded resource-days " + overloadedDaysBefore + " -> " + overloadedDaysAfter;
		}
	}

	private static class Overload {
		final Resource resource;
		final long dayStart;
		Overload(Resource resource, long dayStart) {
			this.resource = resource;
			this.dayStart = dayStart;
		}
	}

	/** Daily work a placed task puts on each of its resources, valid for the dates it had when taken. */
	private static class Contribution {
		long start;
		long end;
		Map<Resource, Map<Long, Long>> daily;
	}

	private final Project project;
	private final Map<Resource, Map<Long, Long>> load = new HashMap<Resource, Map<Long, Long>>();
	private final Map<Task, Contribution> counted = new HashMap<Task, Contribution>();
	private final Map<Task, Set<Task>> predecessorCache = new HashMap<Task, Set<Task>>();

	public SerialResourceLeveler(Project project) {
		this.project = project;
	}

	/** Removes every leveling delay and reschedules. Returns how many tasks were affected. */
	public static int clearLevelingDelays(Project project) {
		int cleared = 0;
		for (Iterator i = project.getTasks().iterator(); i.hasNext();) {
			Task task = (Task) i.next();
			if (task.getLevelingDelay() != 0) {
				task.setLevelingDelay(0);
				cleared++;
			}
		}
		project.recalculate();
		return cleared;
	}

	public Result level() {
		Result result = new Result();
		clearLevelingDelays(project);

		List<Task> candidates = new ArrayList<Task>();
		List<Task> fixed = new ArrayList<Task>();
		for (Iterator i = project.getTasks().iterator(); i.hasNext();) {
			Task task = (Task) i.next();
			if (task.isWbsParent() || !hasLaborAssignments(task))
				continue;
			if (isMovable(task))
				candidates.add(task);
			else
				fixed.add(task);
		}
		result.candidates = candidates.size();
		result.fixed = fixed.size();

		List<Task> all = new ArrayList<Task>(fixed);
		all.addAll(candidates);
		result.overloadedDaysBefore = countOverloadedDays(all, null);

		for (Task task : fixed)
			addToLoad(task);

		Set<Task> unplaced = new LinkedHashSet<Task>(candidates);
		while (!unplaced.isEmpty()) {
			Task task = pickNext(unplaced);
			unplaced.remove(task);
			refreshCounted();
			long delay = 0;
			int moves = 0;
			boolean placed = false;
			while (moves < MAX_MOVES_PER_TASK) {
				Overload overload = firstOverload(task);
				if (overload == null) {
					placed = true;
					break;
				}
				WorkCalendar calendar = task.getEffectiveWorkCalendar();
				long start = task.getStart();
				long afterOverloadedDay = calendar.adjustInsideCalendar(nextDay(overload.dayStart), false);
				long extra = calendar.compare(afterOverloadedDay, start, false);
				if (extra <= 0)
					extra = WorkCalendar.MILLIS_IN_HOUR;
				delay += extra;
				moves++;
				task.setLevelingDelay(delay);
				project.recalculate();
				refreshCounted();
			}
			if (!placed)
				result.unresolved++;
			if (delay > 0) {
				result.delayed++;
				result.totalDelay += delay;
				result.details.add(task.getId() + " " + task.getName() + ": +" + (delay / (double) WorkCalendar.MILLIS_IN_HOUR) + "h" + (placed ? "" : " (unresolved)"));
			}
			addToLoad(task);
		}
		result.overloadedDaysAfter = countOverloadedDays(all, result.remaining);
		return result;
	}

	/** Resource-days on which the current schedule exceeds some resource's capacity. */
	public int countOverloadedDays() {
		List<Task> tasks = new ArrayList<Task>();
		for (Iterator i = project.getTasks().iterator(); i.hasNext();) {
			Task task = (Task) i.next();
			if (!task.isWbsParent() && hasLaborAssignments(task))
				tasks.add(task);
		}
		return countOverloadedDays(tasks, null);
	}

	// --- selection -----------------------------------------------------------------

	private boolean isMovable(Task task) {
		if (task.getActualStart() != 0 || task.isExternal() || task.isReadOnly())
			return false;
		return priority(task) < DO_NOT_LEVEL_PRIORITY;
	}

	private static Collection assignmentsOf(Task task) {
		return task instanceof NormalTask ? ((NormalTask) task).getAssignments() : java.util.Collections.EMPTY_LIST;
	}

	private static int priority(Task task) {
		return task instanceof NormalTask ? ((NormalTask) task).getPriority() : DEFAULT_PRIORITY;
	}

	private static boolean hasLaborAssignments(Task task) {
		for (Iterator i = assignmentsOf(task).iterator(); i.hasNext();) {
			Assignment assignment = (Assignment) i.next();
			if (assignment.isLabor() && !assignment.isDefault())
				return true;
		}
		return false;
	}

	/** Highest priority, then earliest start, among the tasks whose predecessors are all placed. */
	private Task pickNext(Set<Task> unplaced) {
		Task best = null;
		boolean bestReady = false;
		for (Task task : unplaced) {
			boolean ready = true;
			for (Task predecessor : effectivePredecessors(task)) {
				if (unplaced.contains(predecessor)) {
					ready = false;
					break;
				}
			}
			if (best == null || (ready && !bestReady) || (ready == bestReady && comesBefore(task, best))) {
				best = task;
				bestReady = ready;
			}
		}
		return best;
	}

	private static boolean comesBefore(Task a, Task b) {
		int pa = priority(a), pb = priority(b);
		if (pa != pb)
			return pa > pb;
		if (a.getStart() != b.getStart())
			return a.getStart() < b.getStart();
		return a.getId() < b.getId();
	}

	/** Leaf tasks that drive this task's start: its own predecessors and those of its ancestors. */
	private Set<Task> effectivePredecessors(Task task) {
		Set<Task> result = predecessorCache.get(task);
		if (result != null)
			return result;
		result = new HashSet<Task>();
		for (Task t = task; t != null; t = t.getWbsParentTask()) {
			for (Iterator i = t.getPredecessorList().iterator(); i.hasNext();) {
				Dependency dependency = (Dependency) i.next();
				if (dependency.isDisabled())
					continue;
				HasDependencies predecessor = dependency.getPredecessor();
				if (predecessor instanceof Task)
					addLeaves((Task) predecessor, result);
			}
		}
		result.remove(task);
		predecessorCache.put(task, result);
		return result;
	}

	private static void addLeaves(Task task, Set<Task> into) {
		if (!task.isWbsParent()) {
			into.add(task);
			return;
		}
		for (Iterator i = task.getWbsChildrenTasks().iterator(); i.hasNext();)
			addLeaves((Task) i.next(), into);
	}

	// --- load bookkeeping -------------------------------------------------------------

	private Overload firstOverload(Task task) {
		long end = task.getEnd();
		for (Iterator i = assignmentsOf(task).iterator(); i.hasNext();) {
			Assignment assignment = (Assignment) i.next();
			if (!assignment.isLabor() || assignment.isDefault())
				continue;
			Resource resource = assignment.getResource();
			Map<Long, Long> resourceLoad = load.get(resource);
			for (long day = dayStart(task.getStart()); day < end; day = nextDay(day)) {
				long work = workOn(assignment, day);
				if (work <= 0)
					continue;
				long existing = resourceLoad == null || resourceLoad.get(day) == null ? 0 : resourceLoad.get(day);
				if (existing + work > capacityOn(resource, day) + TOLERANCE)
					return new Overload(resource, day);
			}
		}
		return null;
	}

	private void addToLoad(Task task) {
		Contribution contribution = contributionOf(task);
		apply(contribution, 1);
		counted.put(task, contribution);
	}

	/** Re-snapshots any counted task whose dates moved since it was taken. */
	private void refreshCounted() {
		for (Map.Entry<Task, Contribution> entry : counted.entrySet()) {
			Task task = entry.getKey();
			Contribution old = entry.getValue();
			if (old.start == task.getStart() && old.end == task.getEnd())
				continue;
			apply(old, -1);
			Contribution fresh = contributionOf(task);
			apply(fresh, 1);
			entry.setValue(fresh);
		}
	}

	private void apply(Contribution contribution, int sign) {
		for (Map.Entry<Resource, Map<Long, Long>> byResource : contribution.daily.entrySet()) {
			Map<Long, Long> resourceLoad = load.get(byResource.getKey());
			if (resourceLoad == null) {
				resourceLoad = new HashMap<Long, Long>();
				load.put(byResource.getKey(), resourceLoad);
			}
			for (Map.Entry<Long, Long> byDay : byResource.getValue().entrySet()) {
				Long current = resourceLoad.get(byDay.getKey());
				resourceLoad.put(byDay.getKey(), (current == null ? 0 : current) + sign * byDay.getValue());
			}
		}
	}

	private Contribution contributionOf(Task task) {
		Contribution contribution = new Contribution();
		contribution.start = task.getStart();
		contribution.end = task.getEnd();
		contribution.daily = new HashMap<Resource, Map<Long, Long>>();
		for (Iterator i = assignmentsOf(task).iterator(); i.hasNext();) {
			Assignment assignment = (Assignment) i.next();
			if (!assignment.isLabor() || assignment.isDefault())
				continue;
			Map<Long, Long> days = contribution.daily.get(assignment.getResource());
			if (days == null) {
				days = new HashMap<Long, Long>();
				contribution.daily.put(assignment.getResource(), days);
			}
			for (long day = dayStart(contribution.start); day < contribution.end; day = nextDay(day)) {
				long work = workOn(assignment, day);
				if (work > 0) {
					Long current = days.get(day);
					days.put(day, (current == null ? 0 : current) + work);
				}
			}
		}
		return contribution;
	}

	/**
	 * Resource-days on which the given tasks together exceed the resource capacity. When
	 * {@code report} is given, one line per such day is added naming the tasks involved.
	 */
	private int countOverloadedDays(Collection<Task> tasks, List<String> report) {
		Map<Resource, Map<Long, Long>> total = new HashMap<Resource, Map<Long, Long>>();
		Map<Resource, Map<Long, List<Task>>> contributors = new HashMap<Resource, Map<Long, List<Task>>>();
		for (Task task : tasks) {
			for (Map.Entry<Resource, Map<Long, Long>> byResource : contributionOf(task).daily.entrySet()) {
				Map<Long, Long> days = total.get(byResource.getKey());
				Map<Long, List<Task>> who = contributors.get(byResource.getKey());
				if (days == null) {
					days = new HashMap<Long, Long>();
					total.put(byResource.getKey(), days);
					who = new HashMap<Long, List<Task>>();
					contributors.put(byResource.getKey(), who);
				}
				for (Map.Entry<Long, Long> byDay : byResource.getValue().entrySet()) {
					Long current = days.get(byDay.getKey());
					days.put(byDay.getKey(), (current == null ? 0 : current) + byDay.getValue());
					List<Task> list = who.get(byDay.getKey());
					if (list == null) {
						list = new ArrayList<Task>();
						who.put(byDay.getKey(), list);
					}
					list.add(task);
				}
			}
		}
		int overloaded = 0;
		java.text.DateFormat dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);
		for (Map.Entry<Resource, Map<Long, Long>> byResource : total.entrySet()) {
			Resource resource = byResource.getKey();
			for (Map.Entry<Long, Long> byDay : byResource.getValue().entrySet()) {
				long capacity = capacityOn(resource, byDay.getKey());
				if (byDay.getValue() <= capacity + TOLERANCE)
					continue;
				overloaded++;
				if (report == null)
					continue;
				StringBuffer line = new StringBuffer();
				line.append(resource.getName()).append(' ').append(dateFormat.format(new java.util.Date(byDay.getKey())));
				line.append(": ").append(byDay.getValue() / (double) WorkCalendar.MILLIS_IN_HOUR).append("h of ")
					.append(capacity / (double) WorkCalendar.MILLIS_IN_HOUR).append("h -");
				for (Task task : contributors.get(resource).get(byDay.getKey())) {
					line.append(' ').append(task.getId());
					if (!isMovable(task))
						line.append('*');
				}
				report.add(line.toString());
			}
		}
		if (report != null)
			java.util.Collections.sort(report);
		return overloaded;
	}

	// --- time helpers -------------------------------------------------------------------

	private static long workOn(Assignment assignment, long dayStart) {
		FieldContext context = new FieldContext();
		context.setInterval(new MutableInterval(dayStart, nextDay(dayStart)));
		return assignment.getWork(context);
	}

	private static long capacityOn(Resource resource, long dayStart) {
		WorkCalendar calendar = resource.getEffectiveWorkCalendar();
		long workingTime = calendar.compare(nextDay(dayStart), dayStart, false);
		return (long) (workingTime * resource.getMaximumUnits());
	}

	private static long dayStart(long date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(date);
		calendar.set(Calendar.HOUR_OF_DAY, 0);
		calendar.set(Calendar.MINUTE, 0);
		calendar.set(Calendar.SECOND, 0);
		calendar.set(Calendar.MILLISECOND, 0);
		return calendar.getTimeInMillis();
	}

	private static long nextDay(long dayStart) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(dayStart);
		calendar.add(Calendar.DATE, 1);
		return calendar.getTimeInMillis();
	}
}
