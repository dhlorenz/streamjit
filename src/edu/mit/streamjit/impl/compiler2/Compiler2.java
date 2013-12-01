package edu.mit.streamjit.impl.compiler2;

import com.google.common.base.Function;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.math.IntMath;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Primitives;
import com.google.common.reflect.TypeResolver;
import com.google.common.reflect.TypeToken;
import edu.mit.streamjit.api.DuplicateSplitter;
import edu.mit.streamjit.api.IllegalStreamGraphException;
import edu.mit.streamjit.api.Joiner;
import edu.mit.streamjit.api.RoundrobinJoiner;
import edu.mit.streamjit.api.RoundrobinSplitter;
import edu.mit.streamjit.api.Splitter;
import edu.mit.streamjit.api.StreamCompilationFailedException;
import edu.mit.streamjit.api.StreamCompiler;
import edu.mit.streamjit.api.WeightedRoundrobinJoiner;
import edu.mit.streamjit.api.WeightedRoundrobinSplitter;
import edu.mit.streamjit.api.Worker;
import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.Buffer;
import edu.mit.streamjit.impl.blob.DrainData;
import edu.mit.streamjit.impl.common.BlobHostStreamCompiler;
import edu.mit.streamjit.impl.common.Configuration;
import edu.mit.streamjit.impl.common.Configuration.IntParameter;
import edu.mit.streamjit.impl.common.Configuration.SwitchParameter;
import edu.mit.streamjit.impl.common.Workers;
import edu.mit.streamjit.impl.compiler.Schedule;
import edu.mit.streamjit.impl.compiler2.Compiler2BlobHost.DrainInstruction;
import edu.mit.streamjit.impl.compiler2.Compiler2BlobHost.ReadInstruction;
import edu.mit.streamjit.impl.compiler2.Compiler2BlobHost.WriteInstruction;
import edu.mit.streamjit.test.Benchmark;
import edu.mit.streamjit.test.Benchmarker;
import edu.mit.streamjit.test.apps.fmradio.FMRadio;
import edu.mit.streamjit.test.regression.Reg20131116_104433_226;
import edu.mit.streamjit.test.sanity.PipelineSanity;
import edu.mit.streamjit.test.sanity.SplitjoinComputeSanity;
import edu.mit.streamjit.util.CollectionUtils;
import edu.mit.streamjit.util.Combinators;
import static edu.mit.streamjit.util.Combinators.*;
import static edu.mit.streamjit.util.LookupUtils.findStatic;
import edu.mit.streamjit.util.Pair;
import edu.mit.streamjit.util.ReflectionUtils;
import edu.mit.streamjit.util.bytecode.Module;
import edu.mit.streamjit.util.bytecode.ModuleClassLoader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 9/22/2013
 */
public class Compiler2 {
	public static final ImmutableSet<Class<?>> REMOVABLE_WORKERS = ImmutableSet.<Class<?>>of(
			RoundrobinSplitter.class, WeightedRoundrobinSplitter.class, DuplicateSplitter.class,
			RoundrobinJoiner.class, WeightedRoundrobinJoiner.class);
	private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
	private static final AtomicInteger PACKAGE_NUMBER = new AtomicInteger();
	private final ImmutableSet<Worker<?, ?>> workers;
	private final ImmutableSet<ActorArchetype> archetypes;
	private final NavigableSet<Actor> actors;
	private ImmutableSortedSet<ActorGroup> groups;
	private ImmutableSortedSet<WorkerActor> actorsToBeRemoved;
	private final Configuration config;
	private final int maxNumCores;
	private final DrainData initialState;
	private final ImmutableMap<Token, ImmutableList<Object>> initialStateDataMap;
	private final Set<Storage> storage;
	private ImmutableMap<ActorGroup, Integer> externalSchedule;
	private final Module module = new Module();
	private ImmutableMap<ActorGroup, Integer> initSchedule;
	/**
	 * For each token in the blob, the number of items live on that edge after
	 * the init schedule, without regard to removals.  (We could recover this
	 * information from Actor.inputSlots when we're creating drain instructions,
	 * but storing it simplifies the code and permits asserting we didn't lose
	 * any items.)
	 */
	private ImmutableMap<Token, Integer> postInitLiveness;
	/**
	 * ConcreteStorage instances used during initialization (bound into the
	 * initialization code).
	 */
	private ImmutableMap<Storage, ConcreteStorage> initStorage;
	/**
	 * ConcreteStorage instances used during the steady-state (bound into the
	 * steady-state code).
	 */
	private ImmutableMap<Storage, ConcreteStorage> steadyStateStorage;
	/**
	 * Code to run the initialization schedule.  (Initialization is
	 * single-threaded.)
	 */
	private MethodHandle initCode;
	/**
	 * Code to run the steady state schedule.  The blob host takes care of
	 * filling/flushing buffers, adjusting storage and the global barrier.
	 */
	private ImmutableList<MethodHandle> steadyStateCode;
	private final List<ReadInstruction> initReadInstructions = new ArrayList<>();
	private final List<WriteInstruction> initWriteInstructions = new ArrayList<>();
	private final List<Runnable> migrationInstructions = new ArrayList<>();
	private final List<ReadInstruction> readInstructions = new ArrayList<>();
	private final List<WriteInstruction> writeInstructions = new ArrayList<>();
	private final List<DrainInstruction> drainInstructions = new ArrayList<>();
	public Compiler2(Set<Worker<?, ?>> workers, Configuration config, int maxNumCores, DrainData initialState) {
		this.workers = ImmutableSet.copyOf(workers);
		Map<Class<?>, ActorArchetype> archetypesBuilder = new HashMap<>();
		Map<Worker<?, ?>, WorkerActor> workerActors = new HashMap<>();
		for (Worker<?, ?> w : workers) {
			@SuppressWarnings("unchecked")
			Class<? extends Worker<?, ?>> wClass = (Class<? extends Worker<?, ?>>)w.getClass();
			if (archetypesBuilder.get(wClass) == null)
				archetypesBuilder.put(wClass, new ActorArchetype(wClass, module));
			WorkerActor actor = new WorkerActor(w, archetypesBuilder.get(wClass));
			workerActors.put(w, actor);
		}
		this.archetypes = ImmutableSet.copyOf(archetypesBuilder.values());

		Map<Token, TokenActor> tokenActors = new HashMap<>();
		Table<Actor, Actor, Storage> storageTable = HashBasedTable.create();
		int[] inputTokenId = new int[]{Integer.MIN_VALUE}, outputTokenId = new int[]{Integer.MAX_VALUE};
		for (WorkerActor a : workerActors.values())
			a.connect(ImmutableMap.copyOf(workerActors), tokenActors, storageTable, inputTokenId, outputTokenId);
		this.actors = new TreeSet<>();
		this.actors.addAll(workerActors.values());
		this.actors.addAll(tokenActors.values());
		this.storage = new HashSet<>(storageTable.values());

		this.config = config;
		this.maxNumCores = maxNumCores;
		this.initialState = initialState;
		ImmutableMap.Builder<Token, ImmutableList<Object>> initialStateDataMapBuilder = ImmutableMap.builder();
		if (initialState != null) {
			for (Table.Cell<Actor, Actor, Storage> cell : storageTable.cellSet()) {
				Token tok;
				if (cell.getRowKey() instanceof TokenActor)
					tok = ((TokenActor)cell.getRowKey()).token();
				else if (cell.getColumnKey() instanceof TokenActor)
					tok = ((TokenActor)cell.getColumnKey()).token();
				else
					tok = new Token(((WorkerActor)cell.getRowKey()).worker(),
							((WorkerActor)cell.getColumnKey()).worker());
				ImmutableList<Object> data = initialState.getData(tok);
				if (data != null && !data.isEmpty()) {
					initialStateDataMapBuilder.put(tok, data);
					cell.getValue().initialData().add(Pair.make(data, MethodHandles.identity(int.class)));
				}
			}
		}
		this.initialStateDataMap = initialStateDataMapBuilder.build();
	}

	public Blob compile() {
		findRemovals();
		fuse();
		schedule();

//		identityRemoval();
		splitterRemoval();
		joinerRemoval();

		inferTypes();
//		unbox();

		generateArchetypalCode();
		createInitCode();
		createSteadyStateCode();
		return instantiateBlob();
	}

	private void findRemovals() {
		ImmutableSortedSet.Builder<WorkerActor> builder = ImmutableSortedSet.naturalOrder();
		for (WorkerActor a : Iterables.filter(actors, WorkerActor.class)) {
			SwitchParameter<Boolean> param = config.getParameter("remove"+a.id(), SwitchParameter.class, Boolean.class);
			if (param != null && param.getValue()) {
				assert REMOVABLE_WORKERS.contains(a.worker().getClass()) : a;
				builder.add(a);
			}
		}
		this.actorsToBeRemoved = builder.build();
	}

	/**
	 * Fuses actors into groups as directed by the configuration.
	 */
	private void fuse() {
		List<ActorGroup> actorGroups = new ArrayList<>();
		for (Actor a : actors)
			actorGroups.add(ActorGroup.of(a));

		//Fuse as much as possible.
		just_fused: do {
			try_fuse: for (Iterator<ActorGroup> it = actorGroups.iterator(); it.hasNext();) {
				ActorGroup g = it.next();
				if (g.isTokenGroup())
					continue try_fuse;
				for (ActorGroup pg : g.predecessorGroups())
					if (pg.isTokenGroup())
						continue try_fuse;
				if (g.isPeeking() || g.predecessorGroups().size() > 1)
					continue try_fuse;
				for (Storage s : g.inputs())
					if (!s.initialData().isEmpty())
						continue try_fuse;

				String paramName = String.format("fuse%d", g.id());
				SwitchParameter<Boolean> fuseParam = config.getParameter(paramName, SwitchParameter.class, Boolean.class);
				if (!fuseParam.getValue())
					continue try_fuse;

				ActorGroup gpred = Iterables.getOnlyElement(g.predecessorGroups());
				ActorGroup fusedGroup = ActorGroup.fuse(g, gpred);
				it.remove();
				actorGroups.remove(gpred);
				actorGroups.add(fusedGroup);
				continue just_fused;
			}
			break;
		} while (true);

		this.groups = ImmutableSortedSet.copyOf(actorGroups);
	}

	/**
	 * Computes each group's internal schedule and the external schedule.
	 */
	private void schedule() {
		for (ActorGroup g : groups)
			internalSchedule(g);
		externalSchedule();
		initSchedule();
	}

	private void externalSchedule() {
		Schedule.Builder<ActorGroup> scheduleBuilder = Schedule.builder();
		scheduleBuilder.addAll(groups);
		for (ActorGroup g : groups) {
			for (Storage e : g.outputs()) {
				Actor upstream = Iterables.getOnlyElement(e.upstream());
				Actor downstream = Iterables.getOnlyElement(e.downstream());
				ActorGroup other = downstream.group();
				int upstreamAdjust = g.schedule().get(upstream);
				int downstreamAdjust = other.schedule().get(downstream);
				scheduleBuilder.connect(g, other)
						.push(e.push() * upstreamAdjust)
						.pop(e.pop() * downstreamAdjust)
						.peek(e.peek() * downstreamAdjust)
						.bufferExactly(0);
			}
		}
		scheduleBuilder.multiply(config.getParameter("multiplier", IntParameter.class).getValue());
		try {
			externalSchedule = scheduleBuilder.build().getSchedule();
		} catch (Schedule.ScheduleException ex) {
			throw new StreamCompilationFailedException("couldn't find external schedule", ex);
		}
	}

	/**
	 * Computes the internal schedule for the given group.
	 */
	private void internalSchedule(ActorGroup g) {
		Schedule.Builder<Actor> scheduleBuilder = Schedule.builder();
		scheduleBuilder.addAll(g.actors());
		for (Storage s : g.internalEdges()) {
			scheduleBuilder.connect(Iterables.getOnlyElement(s.upstream()), Iterables.getOnlyElement(s.downstream()))
					.push(s.push())
					.pop(s.pop())
					.peek(s.peek())
					.bufferExactly(0);
		}

		try {
			Schedule<Actor> schedule = scheduleBuilder.build();
			g.setSchedule(schedule.getSchedule());
		} catch (Schedule.ScheduleException ex) {
			throw new StreamCompilationFailedException("couldn't find internal schedule for group "+g.id(), ex);
		}
	}

	private void initSchedule() {
		Schedule.Builder<ActorGroup> scheduleBuilder = Schedule.builder();
		scheduleBuilder.addAll(groups);
		for (Storage s : storage) {
			if (s.isInternal()) continue;
			Actor upstream = Iterables.getOnlyElement(s.upstream()), downstream = Iterables.getOnlyElement(s.downstream());
			int upstreamAdjust = upstream.group().schedule().get(upstream);
			int downstreamAdjust = downstream.group().schedule().get(downstream);
			int throughput, excessPeeks;
			//TODO: avoid double-buffering token groups here?
			if (actorsToBeRemoved.contains(downstream) && false)
				throughput = excessPeeks = 0;
			else {
				throughput = s.push() * upstreamAdjust * externalSchedule.get(upstream.group());
				excessPeeks = Math.max(s.peek() - s.pop(), 0);
			}
			int initialDataSize = Iterables.getOnlyElement(s.initialData(), new Pair<>(ImmutableList.<Object>of(), (MethodHandle)null)).first.size();
			scheduleBuilder.connect(upstream.group(), downstream.group())
					.push(s.push() * upstreamAdjust)
					.pop(s.pop() * downstreamAdjust)
					.peek(s.peek() * downstreamAdjust)
					.bufferAtLeast(throughput + excessPeeks - initialDataSize);
		}

		try {
			Schedule<ActorGroup> schedule = scheduleBuilder.build();
			this.initSchedule = schedule.getSchedule();
		} catch (Schedule.ScheduleException ex) {
			throw new StreamCompilationFailedException("couldn't find init schedule", ex);
		}

		ImmutableMap.Builder<Token, Integer> postInitLivenessBuilder = ImmutableMap.builder();
		for (Storage s : storage) {
			if (s.isInternal()) continue;
			Actor upstream = Iterables.getOnlyElement(s.upstream()), downstream = Iterables.getOnlyElement(s.downstream());
			int upstreamExecutions = upstream.group().schedule().get(upstream) * initSchedule.get(upstream.group());
			int downstreamExecutions = downstream.group().schedule().get(downstream) * initSchedule.get(downstream.group());
			int liveItems = s.push() * upstreamExecutions - s.pop() * downstreamExecutions;
			assert liveItems >= 0 : s;

			int index = downstream.inputs().indexOf(s);
			assert index != -1;
			Token token;
			if (downstream instanceof WorkerActor) {
				Worker<?, ?> w = ((WorkerActor)downstream).worker();
				token = downstream.id() == 0 ? Token.createOverallInputToken(w) :
						new Token(Workers.getPredecessors(w).get(index), w);
			} else
				token = ((TokenActor)downstream).token();
			for (int i = 0; i < liveItems; ++i)
				downstream.inputSlots(index).add(StorageSlot.live(token, i));
			postInitLivenessBuilder.put(token, liveItems);
		}
		this.postInitLiveness = postInitLivenessBuilder.build();
	}

	private void splitterRemoval() {
		for (WorkerActor splitter : actorsToBeRemoved) {
			if (!(splitter.worker() instanceof Splitter)) continue;
			List<MethodHandle> transfers = splitterTransferFunctions(splitter);
			Storage survivor = Iterables.getOnlyElement(splitter.inputs());
			//Remove all instances of splitter, not just the first.
			survivor.downstream().removeAll(ImmutableList.of(splitter));
			MethodHandle Sin = Iterables.getOnlyElement(splitter.inputIndexFunctions());
			List<StorageSlot> drainInfo = splitter.inputSlots(0);
			for (int i = 0; i < splitter.outputs().size(); ++i) {
				Storage victim = splitter.outputs().get(i);
				MethodHandle t = transfers.get(i);
				for (Actor a : victim.downstream()) {
					List<Storage> inputs = a.inputs();
					List<MethodHandle> inputIndices = a.inputIndexFunctions();
					for (int j = 0; j < inputs.size(); ++j)
						if (inputs.get(j).equals(victim)) {
							inputs.set(j, survivor);
							survivor.downstream().add(a);
							inputIndices.set(j, MethodHandles.filterReturnValue(inputIndices.get(j), t));
							for (int idx = 0, q = a.translateInputIndex(j, idx); q < drainInfo.size(); ++idx, q = a.translateInputIndex(j, idx)) {
								a.inputSlots(j).add(drainInfo.get(q));
								drainInfo.set(q, drainInfo.get(q).duplify());
							}
							inputIndices.set(j, MethodHandles.filterReturnValue(inputIndices.get(j), Sin));
						}
				}
				//TODO: victim initial data
				storage.remove(victim);
			}

			removeActor(splitter);
			assert consistency();
		}
	}

	/**
	 * Returns transfer functions for the given splitter.
	 *
	 * A splitter has one transfer function for each output that maps logical
	 * output indices to logical input indices (representing the splitter's
	 * distribution pattern).
	 * @param a an actor
	 * @return transfer functions, or null
	 */
	private List<MethodHandle> splitterTransferFunctions(WorkerActor a) {
		assert REMOVABLE_WORKERS.contains(a.worker().getClass()) : a.worker().getClass();
		if (a.worker() instanceof RoundrobinSplitter || a.worker() instanceof WeightedRoundrobinSplitter) {
			int[] weights = new int[a.outputs().size()];
			for (int i = 0; i < weights.length; ++i)
				weights[i] = a.push(i);
			return roundrobinTransferFunctions(weights);
		} else if (a.worker() instanceof DuplicateSplitter) {
			return Collections.nCopies(a.outputs().size(), MethodHandles.identity(int.class));
		} else
			throw new AssertionError();
	}

	private void joinerRemoval() {
		for (WorkerActor joiner : actorsToBeRemoved) {
			if (!(joiner.worker() instanceof Joiner)) continue;
			List<MethodHandle> transfers = joinerTransferFunctions(joiner);
			Storage survivor = Iterables.getOnlyElement(joiner.outputs());
			//Remove all instances of joiner, not just the first.
			survivor.upstream().removeAll(ImmutableList.of(joiner));
			MethodHandle Jout = Iterables.getOnlyElement(joiner.outputIndexFunctions());
			for (int i = 0; i < joiner.inputs().size(); ++i) {
				Storage victim = joiner.inputs().get(i);
				MethodHandle t = transfers.get(i);
				MethodHandle t2 = MethodHandles.filterReturnValue(t, Jout);
				for (Actor a : victim.upstream()) {
					List<Storage> outputs = a.outputs();
					List<MethodHandle> outputIndices = a.outputIndexFunctions();
					for (int j = 0; j < outputs.size(); ++j)
						if (outputs.get(j).equals(victim)) {
							outputs.set(j, survivor);
							outputIndices.set(j, MethodHandles.filterReturnValue(outputIndices.get(j), t2));
							survivor.upstream().add(a);
						}
				}
				//TODO: victim initial data
				storage.remove(victim);
			}

			//Linearize drain info from the joiner's inputs.
			int maxIdx = 0;
			for (int i = 0; i < joiner.inputs().size(); ++i) {
				MethodHandle t = transfers.get(i);
				for (int idx = 0; idx < joiner.inputSlots(i).size(); ++idx)
					try {
						maxIdx = Math.max(maxIdx, (int)t.invokeExact(joiner.inputSlots(i).size()-1));
					} catch (Throwable ex) {
						throw new AssertionError("Can't happen! transfer function threw?", ex);
					}
			}
			List<StorageSlot> linearizedInput = new ArrayList<>(Collections.nCopies(maxIdx+1, StorageSlot.hole()));
			for (int i = 0; i < joiner.inputs().size(); ++i) {
				MethodHandle t = transfers.get(i);
				for (int idx = 0; idx < joiner.inputSlots(i).size(); ++idx)
					try {
						linearizedInput.set((int)t.invokeExact(idx), joiner.inputSlots(i).get(idx));
					} catch (Throwable ex) {
						throw new AssertionError("Can't happen! transfer function threw?", ex);
					}
				joiner.inputSlots(i).clear();
				joiner.inputSlots(i).trimToSize();
			}

			if (!linearizedInput.isEmpty()) {
				for (Actor a : survivor.downstream())
					for (int j = 0; j < a.inputs().size(); ++j)
						if (a.inputs().get(j).equals(survivor))
							for (int idx = 0, q = a.translateInputIndex(j, idx); q < linearizedInput.size(); ++idx, q = a.translateInputIndex(j, idx)) {
								StorageSlot slot = linearizedInput.get(q);
								a.inputSlots(j).add(slot);
								linearizedInput.set(q, slot.duplify());
							}
			}

//			System.out.println("removed "+joiner);
			removeActor(joiner);
			assert consistency();
		}
	}

	private List<MethodHandle> joinerTransferFunctions(WorkerActor a) {
		assert REMOVABLE_WORKERS.contains(a.worker().getClass()) : a.worker().getClass();
		if (a.worker() instanceof RoundrobinJoiner || a.worker() instanceof WeightedRoundrobinJoiner) {
			int[] weights = new int[a.inputs().size()];
			for (int i = 0; i < weights.length; ++i)
				weights[i] = a.pop(i);
			return roundrobinTransferFunctions(weights);
		} else
			throw new AssertionError();
	}

	private List<MethodHandle> roundrobinTransferFunctions(int[] weights) {
		int[] weightPrefixSum = new int[weights.length + 1];
		for (int i = 1; i < weightPrefixSum.length; ++i)
			weightPrefixSum[i] = weightPrefixSum[i-1] + weights[i-1];
		int N = weightPrefixSum[weightPrefixSum.length-1];
		//t_x(i) = N(i/w[x]) + sum_0_x-1{w} + (i mod w[x])
		//where the first two terms select a "window" and the third is the
		//index into that window.
		ImmutableList.Builder<MethodHandle> transfer = ImmutableList.builder();
		for (int x = 0; x < weights.length; ++x)
			transfer.add(MethodHandles.insertArguments(ROUNDROBIN_TRANSFER_FUNCTION, 0, weights[x], weightPrefixSum[x], N));
		return transfer.build();
	}
	private final MethodHandle ROUNDROBIN_TRANSFER_FUNCTION = findStatic(LOOKUP, Compiler2.class, "_roundrobinTransferFunction", int.class, int.class, int.class, int.class, int.class);
	//TODO: build this directly out of MethodHandles?
	private static int _roundrobinTransferFunction(int weight, int prefixSum, int N, int i) {
		return N*(i/weight) + prefixSum + IntMath.mod(i, weight);
	}

	/**
	 * Removes an Actor from this compiler's data structures.  The Actor should
	 * already have been unlinked from the graph (no incoming edges); this takes
	 * care of removing it from the actors set, its actor group (possibly
	 * removing the group if it's now empty), and the schedule.
	 * @param a the actor to remove
	 */
	private void removeActor(Actor a) {
		assert actors.contains(a) : a;
		actors.remove(a);
		ActorGroup g = a.group();
		g.remove(a);
		if (g.actors().isEmpty()) {
			groups = ImmutableSortedSet.copyOf(Sets.difference(groups, ImmutableSet.of(g)));
			externalSchedule = ImmutableMap.copyOf(Maps.difference(externalSchedule, ImmutableMap.of(g, 0)).entriesOnlyOnLeft());
			initSchedule = ImmutableMap.copyOf(Maps.difference(initSchedule, ImmutableMap.of(g, 0)).entriesOnlyOnLeft());
		}
	}

	private boolean consistency() {
		Set<Storage> usedStorage = new HashSet<>();
		for (Actor a : actors) {
			usedStorage.addAll(a.inputs());
			usedStorage.addAll(a.outputs());
		}
		if (!storage.equals(usedStorage)) {
			Set<Storage> unused = Sets.difference(storage, usedStorage);
			Set<Storage> untracked = Sets.difference(usedStorage, storage);
			throw new AssertionError(String.format("inconsistent storage:%n\tunused: %s%n\tuntracked:%s%n", unused, untracked));
		}
		return true;
	}

	//<editor-fold defaultstate="collapsed" desc="Unimplemented optimization stuff">
//	/**
//	 * Removes Identity instances from the graph, unless doing so would make the
//	 * graph empty.
//	 */
//	private void identityRemoval() {
//		//TODO: remove from group, possibly removing the group if it becomes empty
//		for (Iterator<Actor> iter = actors.iterator(); iter.hasNext();) {
//			if (actors.size() == 1)
//				break;
//			Actor actor = iter.next();
//			if (!actor.archetype().workerClass().equals(Identity.class))
//				continue;
//
//			iter.remove();
//			assert actor.predecessors().size() == 1 && actor.successors().size() == 1;
//			Object upstream = actor.predecessors().get(0), downstream = actor.successors().get(0);
//			if (upstream instanceof Actor)
//				replace(((Actor)upstream).successors(), actor, downstream);
//			if (downstream instanceof Actor)
//				replace(((Actor)downstream).predecessors(), actor, upstream);
//			//No index function changes required for Identity actors.
//		}
//	}
//
//	private static int replace(List<Object> list, Object target, Object replacement) {
//		int replacements = 0;
//		for (int i = 0; i < list.size(); ++i)
//			if (Objects.equals(list.get(0), target)) {
//				list.set(i, replacement);
//				++replacements;
//			}
//		return replacements;
//	}
	//</editor-fold>

	/**
	 * Performs type inference to replace type variables with concrete types.
	 * For now, we only care about wrapper types.
	 */
	public void inferTypes() {
		while (inferUpward() || inferDownward());
	}

	private boolean inferUpward() {
		boolean changed = false;
		//For each storage, if a reader's input type is a final type, all
		//writers' output types must be that final type.  (Wrappers are final,
		//so this works for wrappers, and maybe detects errors related to other
		//types.)
		for (Storage s : storage) {
			Set<TypeToken<?>> finalInputTypes = new HashSet<>();
			for (Actor a : s.downstream())
				if (Modifier.isFinal(a.inputType().getRawType().getModifiers()))
					finalInputTypes.add(a.inputType());
			if (finalInputTypes.isEmpty()) continue;
			if (finalInputTypes.size() > 1)
				throw new IllegalStreamGraphException("Type mismatch among readers: "+s.downstream());

			TypeToken<?> inputType = finalInputTypes.iterator().next();
			for (Actor a : s.upstream())
				if (!a.outputType().equals(inputType)) {
					TypeToken<?> oldOutputType = a.outputType();
					TypeResolver resolver = new TypeResolver().where(oldOutputType.getType(), inputType.getType());
					TypeToken<?> newOutputType = TypeToken.of(resolver.resolveType(oldOutputType.getType()));
					if (!oldOutputType.equals(newOutputType)) {
						a.setOutputType(newOutputType);
						System.out.println("inferUpward: inferred "+a+" output type: "+oldOutputType+" -> "+newOutputType);
						changed = true;
					}

					TypeToken<?> oldInputType = a.inputType();
					TypeToken<?> newInputType = TypeToken.of(resolver.resolveType(oldInputType.getType()));
					if (!oldInputType.equals(newInputType)) {
						a.setInputType(newInputType);
						System.out.println("inferUpward: inferred "+a+" input type: "+oldInputType+" -> "+newInputType);
						changed = true;
					}
				}
		}
		return changed;
	}

	private boolean inferDownward() {
		boolean changed = false;
		//For each storage, find the most specific common type among all the
		//writers' output types, then if it's final, unify with any variable or
		//wildcard reader input type.  (We only unify if final to avoid removing
		//a type variable too soon.  We could also refine concrete types like
		//Object to a more specific subclass.)
		for (Storage s : storage) {
			Set<? extends TypeToken<?>> commonTypes = null;
			for (Actor a : s.upstream())
				if (commonTypes == null)
					commonTypes = a.outputType().getTypes();
				else
					commonTypes = Sets.intersection(commonTypes, a.outputType().getTypes());
			if (commonTypes.isEmpty())
				throw new IllegalStreamGraphException("No common type among writers: "+s.upstream());

			TypeToken<?> mostSpecificType = commonTypes.iterator().next();
			if (!Modifier.isFinal(mostSpecificType.getRawType().getModifiers()))
				continue;
			for (Actor a : s.downstream()) {
				TypeToken<?> oldInputType = a.inputType();
				//TODO: this isn't quite right?
				if (!ReflectionUtils.containsVariableOrWildcard(oldInputType.getType())) continue;

				TypeResolver resolver = new TypeResolver().where(oldInputType.getType(), mostSpecificType.getType());
				TypeToken<?> newInputType = TypeToken.of(resolver.resolveType(oldInputType.getType()));
				if (!oldInputType.equals(newInputType)) {
					a.setInputType(newInputType);
					System.out.println("inferDownward: inferred "+a+" input type: "+oldInputType+" -> "+newInputType);
					changed = true;
				}

				TypeToken<?> oldOutputType = a.outputType();
				TypeToken<?> newOutputType = TypeToken.of(resolver.resolveType(oldOutputType.getType()));
				if (!oldOutputType.equals(newOutputType)) {
					a.setOutputType(newOutputType);
					System.out.println("inferDownward: inferred "+a+" output type: "+oldOutputType+" -> "+newOutputType);
					changed = true;
				}
			}
		}
		return changed;
	}

	/**
	 * Symbolically unboxes a Storage if its common type is a wrapper type and
	 * all the connected Actors support unboxing.
	 */
	private void unbox() {
		next_storage: for (Storage s : storage) {
			TypeToken<?> commonType = s.commonType();
			if (!Primitives.isWrapperType(commonType.getRawType())) continue;
			for (Actor a : s.upstream())
				if (!a.canUnboxOutput())
					continue next_storage;
			for (Actor a : s.downstream())
				if (!a.canUnboxInput())
					continue next_storage;
			Class<?> type = commonType.unwrap().getRawType();
			s.setType(type);
//			System.out.println("unboxed "+s+" to "+type);
		}
	}

	private void generateArchetypalCode() {
		String packageName = "compiler"+PACKAGE_NUMBER.getAndIncrement();
		ModuleClassLoader mcl = new ModuleClassLoader(module);
		for (ActorArchetype archetype : archetypes)
			archetype.generateCode(packageName, mcl);
	}

	private void createInitCode() {
		ImmutableMap<Actor, ImmutableList<MethodHandle>> indexFxnBackup = adjustOutputIndexFunctions(new Function<Storage, Set<Integer>>() {
			@Override
			public Set<Integer> apply(Storage input) {
				return input.indicesLiveBeforeInit();
			}
		});

		this.initStorage = createExternalStorage(MapConcreteStorage.initFactory());
		initReadInstructions.add(new InitDataReadInstruction(initStorage, initialStateDataMap));

		/**
		 * During init, all (nontoken) groups are assigned to the same Core in
		 * topological order (via the ordering on ActorGroups).  At the same
		 * time we build the token init schedule information required by the
		 * blob host.
		 */
		Core initCore = new Core(storage, initStorage, MapConcreteStorage.initFactory());
		for (ActorGroup g : groups)
			if (!g.isTokenGroup())
				initCore.allocate(g, Range.closedOpen(0, initSchedule.get(g)));
			else {
				assert g.actors().size() == 1;
				TokenActor ta = (TokenActor)g.actors().iterator().next();
				assert g.schedule().get(ta) == 1;
				ConcreteStorage storage = initStorage.get(Iterables.getOnlyElement(ta.isInput() ? g.outputs() : g.inputs()));
				int executions = initSchedule.get(g);
				if (ta.isInput())
					initReadInstructions.add(new TokenReadInstruction(g, storage, executions));
				else
					initWriteInstructions.add(new TokenWriteInstruction(g, storage, executions));
			}
		this.initCode = initCore.code();

		restoreOutputIndexFunctions(indexFxnBackup);
	}

	private void createSteadyStateCode() {
		for (Actor a : actors) {
			for (int i = 0; i < a.outputs().size(); ++i) {
				Storage s = a.outputs().get(i);
				if (s.isInternal()) continue;
				int itemsWritten = a.push(i) * initSchedule.get(a.group()) * a.group().schedule().get(a);
				a.outputIndexFunctions().set(i, MethodHandles.filterArguments(
						a.outputIndexFunctions().get(i), 0, Combinators.add(MethodHandles.identity(int.class), itemsWritten)));
			}
			for (int i = 0; i < a.inputs().size(); ++i) {
				Storage s = a.inputs().get(i);
				if (s.isInternal()) continue;
				int itemsRead = a.pop(i) * initSchedule.get(a.group()) * a.group().schedule().get(a);
				a.inputIndexFunctions().set(i, MethodHandles.filterArguments(
						a.inputIndexFunctions().get(i), 0, Combinators.add(MethodHandles.identity(int.class), itemsRead)));
			}
		}

		for (Storage s : storage)
			s.computeSteadyStateRequirements(externalSchedule);
		this.steadyStateStorage = createExternalStorage(CircularArrayConcreteStorage.factory());

		List<Core> ssCores = new ArrayList<>(maxNumCores);
		for (int i = 0; i < maxNumCores; ++i)
			ssCores.add(new Core(storage, steadyStateStorage, CircularArrayConcreteStorage.factory()));
		for (ActorGroup g : groups)
			if (!g.isTokenGroup())
				allocateGroup(g, ssCores);
			else {
				assert g.actors().size() == 1;
				TokenActor ta = (TokenActor)g.actors().iterator().next();
				assert g.schedule().get(ta) == 1;
				ConcreteStorage storage = steadyStateStorage.get(Iterables.getOnlyElement(ta.isInput() ? g.outputs() : g.inputs()));
				int executions = externalSchedule.get(g);
				if (ta.isInput())
					readInstructions.add(new TokenReadInstruction(g, storage, executions));
				else
					writeInstructions.add(new TokenWriteInstruction(g, storage, executions));
			}
		ImmutableList.Builder<MethodHandle> steadyStateCodeBuilder = ImmutableList.builder();
		for (Core c : ssCores)
			if (!c.isEmpty())
				steadyStateCodeBuilder.add(c.code());
		this.steadyStateCode = steadyStateCodeBuilder.build();

		/**
		 * Create migration instructions: Runnables that move live items from
		 * initialization to steady-state storage.
		 */
		for (Storage s : initStorage.keySet())
			migrationInstructions.add(new MigrationInstruction(
					s, initStorage.get(s), steadyStateStorage.get(s)));

		createDrainInstructions();
	}

	/**
	 * Allocates executions of the given group to the given cores (i.e.,
	 * performs data-parallel fission).
	 * @param g the group to fiss
	 * @param cores the cores to fiss over, subject to the configuration
	 */
	private void allocateGroup(ActorGroup g, List<Core> cores) {
		Range<Integer> toBeAllocated = Range.closedOpen(0, externalSchedule.get(g));
		for (int core = 0; core < cores.size() && !toBeAllocated.isEmpty(); ++core) {
			String name = String.format("node%dcore%diter", g.id(), core);
			IntParameter parameter = config.getParameter(name, IntParameter.class);
			if (parameter == null || parameter.getValue() == 0) continue;

			//If the node is stateful, we must put all allocations on the
			//same core. Arbitrarily pick the first core with an allocation.
			//(If no cores have an allocation, we'll put them on core 0 below.)
			int min = toBeAllocated.lowerEndpoint();
			Range<Integer> allocation = g.isStateful() ? toBeAllocated :
					toBeAllocated.intersection(Range.closedOpen(min, min + parameter.getValue()));
			cores.get(core).allocate(g, allocation);
			toBeAllocated = Range.closedOpen(allocation.upperEndpoint(), toBeAllocated.upperEndpoint());
		}

		//If we have iterations left over not assigned to a core,
		//arbitrarily put them on core 0.
		if (!toBeAllocated.isEmpty())
			cores.get(0).allocate(g, toBeAllocated);
	}

	/**
	 * Create drain instructions, which collect live items from steady-state
	 * storage when draining.
	 */
	private void createDrainInstructions() {
		Map<Token, List<Pair<ConcreteStorage, Integer>>> drainReads = new HashMap<>();
		for (Map.Entry<Token, Integer> e : postInitLiveness.entrySet())
			drainReads.put(e.getKey(), new ArrayList<>(Collections.nCopies(e.getValue(), (Pair<ConcreteStorage, Integer>)null)));

		for (Actor a : actors) {
			for (int input = 0; input < a.inputs().size(); ++input) {
				ConcreteStorage storage = steadyStateStorage.get(a.inputs().get(input));
				for (int index = 0; index < a.inputSlots(input).size(); ++index) {
					StorageSlot info = a.inputSlots(input).get(index);
					if (info.isDrainable()) {
						Pair<ConcreteStorage, Integer> old = drainReads.get(info.token()).
								set(info.index(), new Pair<>(storage, a.translateInputIndex(input, index)));
						assert old == null : "overwriting "+info;
					}
				}
			}
		}

		for (Map.Entry<Token, List<Pair<ConcreteStorage, Integer>>> e : drainReads.entrySet()) {
			assert !e.getValue().contains(null) : "lost an element from "+e.getKey()+": "+e.getValue();
			drainInstructions.add(new XDrainInstruction(e.getKey(), e.getValue()));
		}
	}

	//<editor-fold defaultstate="collapsed" desc="Output index function adjust/restore">
	/**
	 * Adjust output index functions to avoid overwriting items in external
	 * storage.  For any actor writing to external storage, we find the
	 * first item that doesn't hit the live index set and add that many
	 * (making that logical item 0 for writers).
	 * @param liveIndexExtractor a function that computes the relevant live
	 * index set for the given external storage
	 * @return the old output index functions, to be restored later
	 */
	private ImmutableMap<Actor, ImmutableList<MethodHandle>> adjustOutputIndexFunctions(Function<Storage, Set<Integer>> liveIndexExtractor) {
		ImmutableMap.Builder<Actor, ImmutableList<MethodHandle>> backup = ImmutableMap.builder();
		for (Actor a : actors) {
			backup.put(a, ImmutableList.copyOf(a.outputIndexFunctions()));
			for (int i = 0; i < a.outputs().size(); ++i) {
				Storage s = a.outputs().get(i);
				if (s.isInternal())
					continue;
				Set<Integer> liveIndices = liveIndexExtractor.apply(s);
				assert liveIndices != null : s +" "+liveIndexExtractor;
				int offset = 0;
				while (liveIndices.contains(a.translateOutputIndex(i, offset)))
					++offset;
				//Check future indices are also open (e.g., that we aren't
				//alternating hole/not-hole).
				for (int check = 0; check < 100; ++check)
					assert !liveIndices.contains(a.translateOutputIndex(i, offset + check)) : check;
				a.outputIndexFunctions().set(i, Combinators.add(a.outputIndexFunctions().get(i), offset));
			}
		}
		return backup.build();
	}

	/**
	 * Restores output index functions from a backup returned from
	 * {@link #adjustOutputIndexFunctions(com.google.common.base.Function)}.
	 * @param backup the backup to restore
	 */
	private void restoreOutputIndexFunctions(ImmutableMap<Actor, ImmutableList<MethodHandle>> backup) {
		for (Actor a : actors) {
			ImmutableList<MethodHandle> oldFxns = backup.get(a);
			assert oldFxns != null : "no backup for "+a;
			assert oldFxns.size() == a.outputIndexFunctions().size() : "backup for "+a+" is wrong size";
			Collections.copy(a.outputIndexFunctions(), oldFxns);
		}
	}
	//</editor-fold>

	private ImmutableMap<Storage, ConcreteStorage> createExternalStorage(StorageFactory factory) {
		ImmutableMap.Builder<Storage, ConcreteStorage> builder = ImmutableMap.builder();
		for (Storage s : storage)
			if (!s.isInternal())
				builder.put(s, factory.make(s));
		return builder.build();
	}

	private static final class MigrationInstruction implements Runnable {
		private final ConcreteStorage init, steady;
		private final int[] indicesToMigrate;
		private MigrationInstruction(Storage storage, ConcreteStorage init, ConcreteStorage steady) {
			this.init = init;
			this.steady = steady;
			ImmutableSortedSet.Builder<Integer> builder = ImmutableSortedSet.naturalOrder();
			for (Actor a : storage.downstream())
				for (int i = 0; i < a.inputs().size(); ++i)
					if (a.inputs().get(i).equals(storage))
						for (int idx = 0; idx < a.inputSlots(i).size(); ++idx)
							if (a.inputSlots(i).get(idx).isLive())
								builder.add(a.translateInputIndex(i, idx));
			this.indicesToMigrate = Ints.toArray(builder.build());
		}
		@Override
		public void run() {
			init.sync();
			for (int i : indicesToMigrate)
				steady.write(i, init.read(i));
			steady.sync();
		}
	}

	/**
	 * The X doesn't stand for anything.  I just needed a different name.
	 */
	private static final class XDrainInstruction implements DrainInstruction {
		private final Token token;
		private final ConcreteStorage[] storage;
		private final int[] storageSelector, index;
		private XDrainInstruction(Token token, List<Pair<ConcreteStorage, Integer>> reads) {
			this.token = token;
			Set<ConcreteStorage> set = new HashSet<>();
			for (Pair<ConcreteStorage, Integer> p : reads)
				set.add(p.first);
			this.storage = set.toArray(new ConcreteStorage[set.size()]);
			this.storageSelector = new int[reads.size()];
			this.index = new int[reads.size()];
			for (int i = 0; i < reads.size(); ++i) {
				storageSelector[i] = Arrays.asList(storage).indexOf(reads.get(i).first);
				index[i] = reads.get(i).second;
			}
		}
		@Override
		public Map<Token, Object[]> call() {
			Object[] data = new Object[index.length];
			int idx = 0;
			for (int i = 0; i < index.length; ++i)
				data[idx++] = storage[storageSelector[i]].read(index[i]);
			return ImmutableMap.of(token, data);
		}
	}

	/**
	 * TODO: consider using read/write handles instead of read(), write()?
	 * TODO: if the index function is a contiguous range and the storage is
	 * backed by an array, allow the storage to readAll directly into its array
	 */
	private static final class TokenReadInstruction implements ReadInstruction {
		private final Token token;
		private final MethodHandle idxFxn;
		private final ConcreteStorage storage;
		private final int count;
		private Buffer buffer;
		private TokenReadInstruction(ActorGroup tokenGroup, ConcreteStorage storage, int executions) {
			assert tokenGroup.isTokenGroup();
			TokenActor actor = (TokenActor)Iterables.getOnlyElement(tokenGroup.actors());
			assert actor.isInput();

			this.token = actor.token();
			this.idxFxn = Iterables.getOnlyElement(actor.outputIndexFunctions());
			this.storage = storage;
			this.count = executions;
		}
		@Override
		public void init(Map<Token, Buffer> buffers) {
			this.buffer = buffers.get(token);
			if (buffer == null)
				throw new IllegalArgumentException("no buffer for "+token);
		}
		@Override
		public Map<Token, Integer> getMinimumBufferCapacity() {
			return ImmutableMap.of(token, count);
		}
		@Override
		public boolean load() {
			Object[] data = new Object[count];
			if (!buffer.readAll(data))
				return false;
			for (int i = 0; i < data.length; ++i) {
				int idx;
				try {
					idx = (int)idxFxn.invokeExact(i);
				} catch (Throwable ex) {
					throw new AssertionError("Can't happen! Index functions should not throw", ex);
				}
				storage.write(idx, data[i]);
			}
			storage.sync();
			return true;
		}
		@Override
		public Map<Token, Object[]> unload() {
			Object[] data = new Object[count];
			for (int i = 0; i < data.length; ++i) {
				int idx;
				try {
					idx = (int)idxFxn.invokeExact(i);
				} catch (Throwable ex) {
					throw new AssertionError("Can't happen! Index functions should not throw", ex);
				}
				data[i] = storage.read(idx);
			}
			return ImmutableMap.of(token, data);
		}
	}

	/**
	 * TODO: consider using read handles instead of read()?
	 * TODO: if the index function is a contiguous range and the storage is
	 * backed by an array, allow the storage to writeAll directly from its array
	 */
	private static final class TokenWriteInstruction implements WriteInstruction {
		private final Token token;
		private final MethodHandle idxFxn;
		private final ConcreteStorage storage;
		private final int count;
		private Buffer buffer;
		private TokenWriteInstruction(ActorGroup tokenGroup, ConcreteStorage storage, int executions) {
			assert tokenGroup.isTokenGroup();
			TokenActor actor = (TokenActor)Iterables.getOnlyElement(tokenGroup.actors());
			assert actor.isOutput();

			this.token = actor.token();
			this.idxFxn = Iterables.getOnlyElement(actor.inputIndexFunctions());
			this.storage = storage;
			this.count = executions;
		}
		@Override
		public void init(Map<Token, Buffer> buffers) {
			this.buffer = buffers.get(token);
			if (buffer == null)
				throw new IllegalArgumentException("no buffer for "+token);
		}
		@Override
		public Map<Token, Integer> getMinimumBufferCapacity() {
			return ImmutableMap.of(token, count);
		}
		@Override
		public void run() {
			Object[] data = new Object[count];
			for (int i = 0; i < count; ++i) {
				int idx;
				try {
					idx = (int)idxFxn.invokeExact(i);
				} catch (Throwable ex) {
					throw new AssertionError("Can't happen! Index functions should not throw", ex);
				}
				data[i] = storage.read(idx);
			}
			for (int written = 0; written != data.length;) {
				written += buffer.write(data, written, data.length-written);
			}
		}
	}

	/**
	 * Writes initial data into init storage, or "unloads" it (just returning it
	 * as it was in the DrainData, not actually reading the storage) if we drain
	 * during init.  (Any remaining data after init will be migrated as normal.)
	 * There's only one of these per blob because it returns all the data, and
	 * it should be the first initReadInstruction.
	 */
	private static final class InitDataReadInstruction implements ReadInstruction {
		private final ImmutableMap<ConcreteStorage, ImmutableList<Pair<ImmutableList<Object>, MethodHandle>>> toWrite;
		private final ImmutableMap<Token, ImmutableList<Object>> initialStateDataMap;
		private InitDataReadInstruction(Map<Storage, ConcreteStorage> initStorage, ImmutableMap<Token, ImmutableList<Object>> initialStateDataMap) {
			ImmutableMap.Builder<ConcreteStorage, ImmutableList<Pair<ImmutableList<Object>, MethodHandle>>> toWriteBuilder = ImmutableMap.builder();
			for (Map.Entry<Storage, ConcreteStorage> e : initStorage.entrySet()) {
				Storage s = e.getKey();
				if (s.isInternal()) continue;
				if (s.initialData().isEmpty()) continue;
				toWriteBuilder.put(e.getValue(), ImmutableList.copyOf(s.initialData()));
			}
			this.toWrite = toWriteBuilder.build();
			this.initialStateDataMap = initialStateDataMap;
		}
		@Override
		public void init(Map<Token, Buffer> buffers) {
		}
		@Override
		public Map<Token, Integer> getMinimumBufferCapacity() {
			return ImmutableMap.of();
		}
		@Override
		public boolean load() {
			for (Map.Entry<ConcreteStorage, ImmutableList<Pair<ImmutableList<Object>, MethodHandle>>> e : toWrite.entrySet())
				for (Pair<ImmutableList<Object>, MethodHandle> p : e.getValue())
					for (int i = 0; i < p.first.size(); ++i) {
						int idx;
						try {
							idx = (int)p.second.invokeExact(i);
						} catch (Throwable ex) {
							throw new AssertionError("Can't happen! Index functions should not throw", ex);
						}
						e.getKey().write(idx, p.first.get(i));
					}
			return true;
		}
		@Override
		public Map<Token, Object[]> unload() {
			Map<Token, Object[]> r = new HashMap<>();
			for (Map.Entry<Token, ImmutableList<Object>> e : initialStateDataMap.entrySet())
				r.put(e.getKey(), e.getValue().toArray());
			return r;
		}
	}

	/**
	 * Creates the blob host.  This mostly involves shuffling our state into the
	 * form the blob host wants.
	 * @return the blob
	 */
	public Blob instantiateBlob() {
		ImmutableSortedSet.Builder<Token> inputTokens = ImmutableSortedSet.naturalOrder(),
				outputTokens = ImmutableSortedSet.naturalOrder();
		ImmutableMap.Builder<Token, ConcreteStorage> tokenInitStorage = ImmutableMap.builder(),
				tokenSteadyStateStorage = ImmutableMap.builder();
		for (TokenActor ta : Iterables.filter(actors, TokenActor.class)) {
			(ta.isInput() ? inputTokens : outputTokens).add(ta.token());
			Storage s = ta.isInput() ? Iterables.getOnlyElement(ta.outputs()) : Iterables.getOnlyElement(ta.inputs());
			tokenInitStorage.put(ta.token(), initStorage.get(s));
			tokenSteadyStateStorage.put(ta.token(), steadyStateStorage.get(s));
		}
		ImmutableList.Builder<MethodHandle> storageAdjusts = ImmutableList.builder();
		for (ConcreteStorage s : steadyStateStorage.values())
			storageAdjusts.add(s.adjustHandle());
		return new Compiler2BlobHost(workers, inputTokens.build(), outputTokens.build(),
				initCode, steadyStateCode,
				storageAdjusts.build(),
				initReadInstructions, initWriteInstructions, migrationInstructions,
				readInstructions, writeInstructions, drainInstructions);
	}

//	public static void main(String[] args) {
//		StreamCompiler sc = new Compiler2StreamCompiler();//.multiplier(64).maxNumCores(8);
////		Benchmark bm = new PipelineSanity.Add15();
////		Benchmark bm = new FMRadio.FMRadioBenchmarkProvider().iterator().next();
//		Benchmark bm = new SplitjoinComputeSanity.MultiplyBenchmark();
//		Benchmarker.runBenchmark(bm, sc).get(0).print(System.out);
//	}
	public static void main(String[] args) {
		Benchmarker.runBenchmark(new Reg20131116_104433_226(), new edu.mit.streamjit.impl.compiler2.Compiler2StreamCompiler()).get(0).print(System.out);
	}
}
