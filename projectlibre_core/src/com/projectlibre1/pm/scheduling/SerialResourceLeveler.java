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
 * The run can be limited to a set of resources: only tasks using those resources are
 * moved and only those resources are checked, while every other task keeps its current
 * leveling delay and still counts as load. With {@link #setWithinSlackOnly(boolean)} a task
 * that cannot be resolved within the total slack it had is left where it is and reported
 * as unresolved. Leveling delays of the tasks being placed are
 * reset first, so repeating a run gives the same answer. {@link #captureDelays()} and
 * {@link #restoreDelays(Map)} let a caller preview a run and back out of it.
 */
public class SerialResourceLeveler {
	/** As in MS Project, this priority means "do not level". */
	public static final int DO_NOT_LEVEL_PRIORITY = 1000;
	private static final int DEFAULT_PRIORITY = 500;
	private static final long TOLERANCE = WorkCalendar.MILLIS_IN_MINUTE;
	private static final int MAX_MOVES_PER_TASK = 400;

	/** A task the leveler delayed. */
	public static class Move {
		public final Task task;
		public final long oldStart;
		public final long newStart;
		public final long delay;
		public final boolean resolved;
		Move(Task task, long oldStart, long newStart, long delay, boolean resolved) {
			this.task = task;
			this.oldStart = oldStart;
			this.newStart = newStart;
			this.delay = delay;
			this.resolved = resolved;
		}
	}

	public static class Result {
		public final List<Move> moves = new ArrayList<Move>();
		/** Tasks left in place because moving them would exceed their slack (within-slack mode). */
		final List<Task> heldForSlack = new ArrayList<Task>();
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
	private Set<Resource> scope = null;
	private boolean withinSlackOnly = false;
	private final Map<Resource, Map<Long, Long>> load = new HashMap<Resource, Map<Long, Long>>();
	private final Map<Task, Contribution> counted = new HashMap<Task, Contribution>();
	private final Map<Task, Set<Task>> predecessorCache = new HashMap<Task, Set<Task>>();

	public SerialResourceLeveler(Project project) {
		this.project = project;
	}

	/** Limits leveling to tasks using these resources; null means every resource. */
	public void setScope(Collection<Resource> resources) {
		scope = resources == null ? null : new HashSet<Resource>(resources);
	}

	/** When set, a task is never delayed beyond the total slack it had before the run. */
	public void setWithinSlackOnly(boolean withinSlackOnly) {
		this.withinSlackOnly = withinSlackOnly;
	}

	/** Current leveling delay of every task, for {@link #restoreDelays(Map)}. */
	public Map<Task, Long> captureDelays() {
		Map<Task, Long> delays = new HashMap<Task, Long>();
		for (Iterator i = project.getTasks().iterator(); i.hasNext();) {
			Task task = (Task) i.next();
			delays.put(task, task.getLevelingDelay());
		}
		return delays;
	}

	/** Puts back delays captured earlier and reschedules. Returns how many tasks changed. */
	public int restoreDelays(Map<Task, Long> delays) {
		int changed = 0;
		for (Map.Entry<Task, Long> entry : delays.entrySet()) {
			if (entry.getKey().getLevelingDelay() != entry.getValue()) {
				entry.getKey().setLevelingDelay(entry.getValue());
				changed++;
			}
		}
		project.recalculate();
		return changed;
	}

	/** Removes the leveling delay of every task using a resource in scope and reschedules. */
	public int clearLevelingDelays() {
		int cleared = 0;
		for (Iterator i = project.getTasks().iterator(); i.hasNext();) {
			Task task = (Task) i.next();
			if (task.getLevelingDelay() != 0 && usesScopedResource(task)) {
				task.setLevelingDelay(0);
				cleared++;
			}
		}
		project.recalculate();
		return cleared;
	}

	/** Removes every leveling delay in the project and reschedules. */
	public static int clearLevelingDelays(Project project) {
		return new SerialResourceLeveler(project).clearLevelingDelays();
	}

	public Result level() {
		List<Task> candidates = new ArrayList<Task>();
		List<Task> fixed = new ArrayList<Task>();
		for (Iterator i = project.getTasks().iterator(); i.hasNext();) {
			Task task = (Task) i.next();
			if (task.isWbsParent() || !hasLaborAssignments(task))
				continue;
			if (isMovable(task) && usesScopedResource(task))
				candidates.add(task);
			else
				fixed.add(task);
		}
		// the tasks about to be placed start from scratch; everything else keeps its delay
		for (Task task : candidates)
			if (task.getLevelingDelay() != 0)
				task.setLevelingDelay(0);
		project.recalculate();
		Map<Task, Long> slackBefore = new HashMap<Task, Long>();
		if (withinSlackOnly)
			for (Task task : candidates)
				slackBefore.put(task, task.getTotalSlack());

		List<Task> all = new ArrayList<Task>(fixed);
		all.addAll(candidates);
		int overloadedDaysBefore = countOverloadedDays(all, null);

		Map<Task, Move> moves = new HashMap<Task, Move>();
		Set<Task> unresolved = new LinkedHashSet<Task>();
		Set<Task> held = new LinkedHashSet<Task>();

		// Within slack only: a task that cannot be resolved stays put, and the tasks placed
		// before it did not know that. Hold such tasks in place and place the rest again,
		// until no new one turns up.
		for (int round = 0;; round++) {
			List<Task> toPlace = new ArrayList<Task>(candidates);
			toPlace.removeAll(held);
			List<Task> inPlace = new ArrayList<Task>(fixed);
			inPlace.addAll(held);
			Result pass = place(toPlace, inPlace, slackBefore, round > 0);
			record(pass, moves, unresolved);
			if (!withinSlackOnly || pass.heldForSlack.isEmpty() || round >= 8)
				break;
			held.addAll(pass.heldForSlack);
		}

		// A task accepted earlier can still be moved by a later delay (as-late-as-possible
		// tasks follow their successors, summaries pull their children, constraints shift).
		// Re-place whatever ended up over-allocated anyway, against everything else as it
		// now stands, and flag what is left.
		for (int round = 0; round < 5; round++) {
			Set<Task> conflicting = overloadedTasks(all);
			conflicting.retainAll(candidates);
			conflicting.removeAll(held);
			if (conflicting.isEmpty())
				break;
			List<Task> toPlace = new ArrayList<Task>(conflicting);
			List<Task> inPlace = new ArrayList<Task>(all);
			inPlace.removeAll(conflicting);
			Result pass = place(toPlace, inPlace, slackBefore, true);
			record(pass, moves, unresolved);
			held.addAll(pass.heldForSlack);
		}
		Set<Task> leftover = overloadedTasks(all);
		leftover.retainAll(candidates);
		leftover.removeAll(held);
		unresolved.addAll(leftover);

		Result result = new Result();
		result.candidates = candidates.size();
		result.fixed = fixed.size();
		List<Task> moved = new ArrayList<Task>(moves.keySet());
		java.util.Collections.sort(moved, new java.util.Comparator<Task>() {
			public int compare(Task a, Task b) {
				return a.getId() < b.getId() ? -1 : a.getId() == b.getId() ? 0 : 1;
			}
		});
		for (Task task : moved) {
			Move move = moves.get(task);
			result.moves.add(move);
			result.delayed++;
			result.totalDelay += move.delay;
			result.details.add(task.getId() + " " + task.getName() + ": +" + (move.delay / (double) WorkCalendar.MILLIS_IN_HOUR) + "h" + (move.resolved ? "" : " (unresolved)"));
		}
		for (Task task : held)
			result.details.add(task.getId() + " " + task.getName() + ": not moved (would exceed slack)");
		for (Task task : leftover)
			result.details.add(task.getId() + " " + task.getName() + ": still over-allocated after leveling");
		unresolved.addAll(held);
		result.unresolved = unresolved.size();
		result.overloadedDaysBefore = overloadedDaysBefore;
		result.overloadedDaysAfter = countOverloadedDays(all, result.remaining);
		return result;
	}

	/** Folds one pass into the running picture: a re-placed task replaces its earlier move. */
	private static void record(Result pass, Map<Task, Move> moves, Set<Task> unresolved) {
		for (Move move : pass.moves) {
			moves.put(move.task, move);
			if (move.resolved)
				unresolved.remove(move.task);
			else
				unresolved.add(move.task);
		}
	}

	/** One serial pass: places {@code candidates} against {@code fixed}, which never move. */
	private Result place(List<Task> candidates, List<Task> fixed, Map<Task, Long> slackBefore, boolean resetFirst) {
		Result result = new Result();
		List<Task> heldForSlack = result.heldForSlack;
		if (resetFirst) {
			for (Task task : candidates)
				task.setLevelingDelay(0);
			project.recalculate();
		}
		load.clear();
		counted.clear();
		for (Task task : fixed)
			addToLoad(task);

		Set<Task> unplaced = new LinkedHashSet<Task>(candidates);
		while (!unplaced.isEmpty()) {
			Task task = pickNext(unplaced);
			unplaced.remove(task);
			refreshCounted();
			long oldStart = task.getStart();
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
				if (withinSlackOnly && delay + extra > slackBefore.get(task)) {
					// cannot be resolved without pushing the finish date: leave the task where it was
					delay = 0;
					task.setLevelingDelay(0);
					project.recalculate();
					refreshCounted();
					break;
				}
				delay += extra;
				moves++;
				task.setLevelingDelay(delay);
				project.recalculate();
				refreshCounted();
			}
			if (!placed)
				result.unresolved++;
			if (!placed && delay == 0)
				heldForSlack.add(task);
			if (delay > 0)
				result.moves.add(new Move(task, oldStart, task.getStart(), delay, placed));
			addToLoad(task);
		}
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

	private boolean inScope(Resource resource) {
		return scope == null || scope.contains(resource);
	}

	private boolean usesScopedResource(Task task) {
		if (scope == null)
			return true;
		for (Iterator i = assignmentsOf(task).iterator(); i.hasNext();) {
			Assignment assignment = (Assignment) i.next();
			if (assignment.isLabor() && !assignment.isDefault() && scope.contains(assignment.getResource()))
				return true;
		}
		return false;
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
			for (Task predecessor : prerequisites(task)) {
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

	private boolean comesBefore(Task a, Task b) {
		int ea = effectivePriority(a), eb = effectivePriority(b);
		if (ea != eb)
			return ea > eb;
		int pa = priority(a), pb = priority(b);
		if (pa != pb)
			return pa > pb;
		if (a.getStart() != b.getStart())
			return a.getStart() < b.getStart();
		return a.getId() < b.getId();
	}

	/**
	 * Leaf tasks whose placement decides where this task can go: its predecessors and those
	 * of its ancestors, and for a task scheduled as late as possible also its successors,
	 * since its start follows them.
	 */
	private Set<Task> prerequisites(Task task) {
		Set<Task> result = new HashSet<Task>(effectivePredecessors(task));
		if (task.isReverseScheduled())
			result.addAll(effectiveSuccessors(task));
		result.remove(task);
		return result;
	}

	/** Leaf tasks that drive this task's start: its own predecessors and those of its ancestors. */
	private Set<Task> effectivePredecessors(Task task) {
		return linkedLeaves(task, true, predecessorCache);
	}

	/** Leaf tasks that follow this task: its own successors and those of its ancestors. */
	private Set<Task> effectiveSuccessors(Task task) {
		return linkedLeaves(task, false, successorCache);
	}

	private final Map<Task, Set<Task>> successorCache = new HashMap<Task, Set<Task>>();

	private static Set<Task> linkedLeaves(Task task, boolean predecessors, Map<Task, Set<Task>> cache) {
		Set<Task> result = cache.get(task);
		if (result != null)
			return result;
		result = new HashSet<Task>();
		for (Task t = task; t != null; t = t.getWbsParentTask()) {
			for (Iterator i = (predecessors ? t.getPredecessorList() : t.getSuccessorList()).iterator(); i.hasNext();) {
				Dependency dependency = (Dependency) i.next();
				if (dependency.isDisabled())
					continue;
				HasDependencies other = predecessors ? dependency.getPredecessor() : dependency.getSuccessor();
				if (other instanceof Task)
					addLeaves((Task) other, result);
			}
		}
		result.remove(task);
		cache.put(task, result);
		return result;
	}

	/**
	 * A task is as urgent as the most urgent task that waits for it, so that a high priority
	 * task can win a contested resource through its predecessors as well.
	 */
	private int effectivePriority(Task task) {
		Integer cached = effectivePriorityCache.get(task);
		if (cached != null)
			return cached;
		if (!priorityVisiting.add(task))
			return priority(task); // dependency cycle: fall back to the task's own priority
		int result = priority(task);
		for (Task successor : effectiveSuccessors(task))
			result = Math.max(result, effectivePriority(successor));
		priorityVisiting.remove(task);
		effectivePriorityCache.put(task, result);
		return result;
	}

	private final Map<Task, Integer> effectivePriorityCache = new HashMap<Task, Integer>();
	private final Set<Task> priorityVisiting = new HashSet<Task>();

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
			if (!inScope(resource))
				continue;
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

	/** Tasks taking part in any over-allocated resource-day among the given tasks (scoped resources only). */
	private Set<Task> overloadedTasks(Collection<Task> tasks) {
		Map<Resource, Map<Long, Long>> total = new HashMap<Resource, Map<Long, Long>>();
		Map<Resource, Map<Long, List<Task>>> contributors = new HashMap<Resource, Map<Long, List<Task>>>();
		accumulate(tasks, total, contributors);
		Set<Task> result = new LinkedHashSet<Task>();
		for (Map.Entry<Resource, Map<Long, Long>> byResource : total.entrySet()) {
			if (!inScope(byResource.getKey()))
				continue;
			for (Map.Entry<Long, Long> byDay : byResource.getValue().entrySet())
				if (byDay.getValue() > capacityOn(byResource.getKey(), byDay.getKey()) + TOLERANCE)
					result.addAll(contributors.get(byResource.getKey()).get(byDay.getKey()));
		}
		return result;
	}

	private void accumulate(Collection<Task> tasks, Map<Resource, Map<Long, Long>> total, Map<Resource, Map<Long, List<Task>>> contributors) {
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
	}

	/**
	 * Resource-days on which the given tasks together exceed the resource capacity. When
	 * {@code report} is given, one line per such day is added naming the tasks involved.
	 */
	private int countOverloadedDays(Collection<Task> tasks, List<String> report) {
		Map<Resource, Map<Long, Long>> total = new HashMap<Resource, Map<Long, Long>>();
		Map<Resource, Map<Long, List<Task>>> contributors = new HashMap<Resource, Map<Long, List<Task>>>();
		accumulate(tasks, total, contributors);
		int overloaded = 0;
		java.text.DateFormat dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT);
		for (Map.Entry<Resource, Map<Long, Long>> byResource : total.entrySet()) {
			Resource resource = byResource.getKey();
			if (!inScope(resource))
				continue;
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
