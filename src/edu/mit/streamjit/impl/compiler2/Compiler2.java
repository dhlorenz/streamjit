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
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Primitives;
import edu.mit.streamjit.api.RoundrobinSplitter;
import edu.mit.streamjit.api.StreamCompilationFailedException;
import edu.mit.streamjit.api.StreamCompiler;
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
import edu.mit.streamjit.test.sanity.PipelineSanity;
import edu.mit.streamjit.util.CollectionUtils;
import edu.mit.streamjit.util.Combinators;
import static edu.mit.streamjit.util.Combinators.*;
import edu.mit.streamjit.util.Pair;
import edu.mit.streamjit.util.bytecode.Module;
import edu.mit.streamjit.util.bytecode.ModuleClassLoader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.math.RoundingMode;
import java.util.ArrayList;
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
	private static final AtomicInteger PACKAGE_NUMBER = new AtomicInteger();
	private final ImmutableSet<Worker<?, ?>> workers;
	private final ImmutableSet<ActorArchetype> archetypes;
	private final NavigableSet<Actor> actors;
	private ImmutableSortedSet<ActorGroup> groups;
	private final Configuration config;
	private final int maxNumCores;
	private final DrainData initialState;
	private final ImmutableMap<Token, ImmutableList<Object>> initialStateDataMap;
	private final Set<Storage> storage;
	private ImmutableMap<ActorGroup, Integer> externalSchedule;
	private final Module module = new Module();
	private ImmutableMap<ActorGroup, Integer> initSchedule;
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
		fuse();
		schedule();
//		identityRemoval();
//		splitterRemoval();
		//joinerRemoval();
//		unbox();

		generateArchetypalCode();
		createInitCode();
		createSteadyStateCode();
		return instantiateBlob();
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

	//<editor-fold defaultstate="collapsed" desc="Unimplemented optimization stuff">
//	private void splitterRemoval() {
//		for (Actor splitter : ImmutableSortedSet.copyOf(actors)) {
//			List<MethodHandle> transfers = splitterTransferFunctions(splitter);
//			if (transfers == null) continue;
//			Storage survivor = Iterables.getOnlyElement(splitter.inputs());
//			MethodHandle Sin = Iterables.getOnlyElement(splitter.inputIndexFunctions());
//			for (int i = 0; i < splitter.outputs().size(); ++i) {
//				Storage victim = splitter.outputs().get(i);
//				MethodHandle t = transfers.get(i);
//				MethodHandle t2 = MethodHandles.filterReturnValue(t, Sin);
//				for (Object o : victim.downstream())
//					if (o instanceof Actor) {
//						Actor q = (Actor)o;
//						List<Storage> inputs = q.inputs();
//						List<MethodHandle> inputIndices = q.inputIndexFunctions();
//						for (int j = 0; j < inputs.size(); ++j)
//							if (inputs.get(j).equals(victim)) {
//								inputs.set(j, survivor);
//								inputIndices.set(j, MethodHandles.filterReturnValue(t2, inputIndices.get(j)));
//							}
//					} else if (o instanceof Token) {
//						Token q = (Token)o;
//						tokenInputIndices.put(q, MethodHandles.filterReturnValue(t2, tokenInputIndices.get(q)));
//					} else
//						throw new AssertionError(o);
//			}
//			removeActor(splitter);
//		}
//	}

//	/**
//	 * Returns transfer functions for the given splitter, or null if the actor
//	 * isn't a splitter or isn't one of the built-in splitters or for some other
//	 * reason we can't make transfer functions.
//	 *
//	 * A splitter has one transfer function for each output that maps logical
//	 * output indices to logical input indices (representing the splitter's
//	 * distribution pattern).
//	 * @param a an actor
//	 * @return transfer functions, or null
//	 */
//	private List<MethodHandle> splitterTransferFunctions(Actor a) {
//		if (a.worker() instanceof RoundrobinSplitter) {
//			//Nx, Nx + 1, Nx + 2, ..., Nx+(N-1)
//			int N = a.outputs().size();
//			ImmutableList.Builder<MethodHandle> transfer = ImmutableList.builder();
//			for (int n = 0; n < N; ++n)
//				transfer.add(add(mul(MethodHandles.identity(int.class), N), n));
//			return transfer.build();
//		} else //TODO: WeightedRoundrobinSplitter, DuplicateSplitter
//			return null;
//	}

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
		}
	}

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

//	/**
//	 * Symbolically unboxes a Storage if its common type is a wrapper type and
//	 * all the connected Actors support unboxing.
//	 */
//	private void unbox() {
//		next_storage: for (Storage s : storage) {
//			Class<?> commonType = s.commonType();
//			if (!Primitives.isWrapperType(commonType)) continue;
//			for (Object o : s.upstream())
//				if (o instanceof Actor && !((Actor)o).archetype().canUnboxOutput())
//					continue next_storage;
//			for (Object o : s.downstream())
//				if (o instanceof Actor && !((Actor)o).archetype().canUnboxInput())
//					continue next_storage;
//			s.setType(Primitives.unwrap(s.commonType()));
//		}
//	}
	//</editor-fold>

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

		initSchedule();
		this.initStorage = createExternalStorage(MapConcreteStorage.factory());
		initReadInstructions.add(new InitDataReadInstruction(initStorage, initialStateDataMap));

		/**
		 * During init, all (nontoken) groups are assigned to the same Core in
		 * topological order (via the ordering on ActorGroups).  At the same
		 * time we build the token init schedule information required by the
		 * blob host.
		 */
		Core initCore = new Core(storage, initStorage, MapConcreteStorage.factory());
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
		ImmutableMap<Actor, ImmutableList<MethodHandle>> indexFxnBackup = adjustOutputIndexFunctions(new Function<Storage, Set<Integer>>() {
			@Override
			public Set<Integer> apply(Storage input) {
				return input.indicesLiveDuringSteadyState();
			}
		});

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

		restoreOutputIndexFunctions(indexFxnBackup);
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
		for (Actor a : actors) {
			for (int i = 0; i < a.inputs().size(); ++i) {
				Storage s = a.inputs().get(i);
				if (s.isInternal()) continue;
				ImmutableSortedSet<Integer> liveIndices = s.indicesLiveDuringSteadyState();
				ImmutableSortedSet.Builder<Integer> drainableIndicesBuilder = ImmutableSortedSet.naturalOrder();
				for (int logicalIndex = 0; ; ++logicalIndex) {
					int physicalIndex = a.translateInputIndex(i, logicalIndex);
					if (liveIndices.contains(physicalIndex))
						drainableIndicesBuilder.add(physicalIndex);
					else break; //TODO: assumes strong monotonicity. maybe needs to look at SS iteration units?
				}
				ImmutableSortedSet<Integer> drainableIndices = drainableIndicesBuilder.build();
				if (drainableIndices.isEmpty()) continue;
				//If the indices are contiguous, minimize storage by storing them as a range.
				if (drainableIndices.last() - drainableIndices.first() + 1 == drainableIndices.size())
					drainableIndices = ContiguousSet.create(Range.closed(drainableIndices.first(), drainableIndices.last()), DiscreteDomain.integers());

				Token token;
				if (a instanceof WorkerActor) {
					Worker<?, ?> w = ((WorkerActor)a).worker();
					token = a.id() == 0 ? Token.createOverallInputToken(w) :
							new Token(Workers.getPredecessors(w).get(i), w);
				} else
					token = ((TokenActor)a).token();
				drainInstructions.add(new XDrainInstruction(token, steadyStateStorage.get(s), drainableIndices));
			}
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

	/**
	 * Computes the initialization schedule, which is in terms of ActorGroup
	 * executions.
	 */
	private void initSchedule() {
		Map<Storage, Set<Integer>> requiredReadIndices = new HashMap<>();
		for (Storage s : storage) {
			s.computeRequirements(externalSchedule);
			requiredReadIndices.put(s, new HashSet<>(s.readIndices()));
		}

		/**
		 * Initial state reduces the required read indices.  We'll need the
		 * before-init liveness later, so we keep it around to avoid computing
		 * it twice.
		 *
		 * TODO: if we have more init data than required for reads, we might
		 * consider trying to drain the extra data rather than keep it around
		 * during the steady state.  We will drain it when we finally drain (at
		 * the end of the blob lifetime), so it would only persist for one
		 * configuration in the online tuning setting.
		 */
		Map<Storage, Set<Integer>> initLiveness = new HashMap<>();
		for (Storage s : storage) {
			ImmutableSortedSet<Integer> liveBeforeInit = s.indicesLiveBeforeInit();
			initLiveness.put(s, liveBeforeInit);
			requiredReadIndices.get(s).removeAll(liveBeforeInit);
		}

		/**
		 * Actual init: iterations of each group necessary to fill the required
		 * read indices of the output Storage.
		 */
		Map<ActorGroup, Integer> actualInit = new HashMap<>();
		for (ActorGroup g : groups)
			//TODO: this is assuming we can stop as soon as an iteration doesn't
			//help.  Will this always be true?
			for (int i = 0; ; ++i) {
				boolean changed = false;
				for (Map.Entry<Storage, Set<Integer>> e : g.writes(i).entrySet())
					changed |= requiredReadIndices.get(e.getKey()).removeAll(e.getValue());
				if (!changed) {
					actualInit.put(g, i);
					break;
				}
			}

		/**
		 * Total init, which is actual init plus allowances for downstream's
		 * total init.  Computed bottom-up via reverse iteration on groups.
		 */
		Map<ActorGroup, Integer> totalInit = new HashMap<>();
		for (ActorGroup g : groups.descendingSet()) {
			if (g.successorGroups().isEmpty())
				totalInit.put(g, actualInit.get(g));
			long us = externalSchedule.get(g);
			List<Long> downstreamReqs = new ArrayList<>(g.successorGroups().size() + 1);
			downstreamReqs.add(0L); //Always at least 0.
			for (ActorGroup s : g.successorGroups()) {
				//I think reverse iteration guarantees bottom-up?
				assert totalInit.containsKey(s) : g.id() + " requires " + s.id();
				//them * (us / them) = us; we round up.
				int st = totalInit.get(s);
				int them = externalSchedule.get(s);
				downstreamReqs.add(LongMath.divide(LongMath.checkedMultiply(st, us), them, RoundingMode.CEILING));
			}
			totalInit.put(g, Ints.checkedCast(Collections.max(downstreamReqs) + actualInit.get(g)));
		}

		this.initSchedule = ImmutableMap.copyOf(totalInit);

		/**
		 * Compute the memory requirement for the init schedule. This is the
		 * required read span (difference between the min and max read index),
		 * plus throughput for each steady-state unit (or fraction thereof)
		 * beyond the first, maximized across all writers.
		 *
		 * TODO: what if we have more init data than required reads?
		 * TODO: given that we use MapConcreteStorage during init anyway, should
		 * we bother with computing an init capacity?  (We can use any
		 * dynamically-expanding ConcreteStorage, not necessarily a Map.)
		 */
		for (Storage s : storage) {
			List<Long> size = new ArrayList<>(s.upstream().size());
			for (ActorGroup writer : s.upstreamGroups())
				size.add(LongMath.checkedMultiply(LongMath.divide(totalInit.get(writer), externalSchedule.get(writer), RoundingMode.CEILING) - 1, s.throughput()));
			int initCapacity = Ints.checkedCast(Collections.max(size) +
					s.readIndices().last() - s.readIndices().first());
			s.setInitCapacity(initCapacity);
		}

		/**
		 * Compute post-initialization liveness (data items written during init
		 * that will be read in a future steady-state iteration). These are the
		 * items that must be moved into steady-state storage. We compute by
		 * building the written physical indices during the init schedule
		 * (including initial data items), then building the read physical
		 * indices for future steady-state executions and taking the
		 * intersection.
		 *
		 * TODO: This makes the same assumption as above, that we can stop
		 * translating indices as soon as adding an execution doesn't change the
		 * indices, which may not be true.
		 */
		Map<Storage, Set<Integer>> initWrites = new HashMap<>();
		Map<Storage, Set<Integer>> futureReads = new HashMap<>();
		for (Storage s : storage) {
			initWrites.put(s, new HashSet<>(initLiveness.get(s)));
			futureReads.put(s, new HashSet<Integer>());
		}
		for (ActorGroup g : groups)
			for (int i = 0; i < initSchedule.get(g); ++i)
				for (Map.Entry<Storage, Set<Integer>> writes : g.writes(i).entrySet())
					initWrites.get(writes.getKey()).addAll(writes.getValue());
		for (ActorGroup g : groups) {
			//We run until our read indices don't intersect any of the write
			//indices, at which point we aren't keeping any more elements live.
			boolean progress = true;
			for (int i = initSchedule.get(g); progress; ++i) {
				progress = false;
				for (Map.Entry<Storage, Set<Integer>> reads : g.reads(i).entrySet()) {
					Storage s = reads.getKey();
					Set<Integer> readIndices = reads.getValue();
					Set<Integer> writeIndices = initWrites.get(s);
					if (!Sets.intersection(readIndices, writeIndices).isEmpty()) {
						futureReads.get(s).addAll(readIndices);
						progress = true;
					}
				}
			}
		}
		for (Storage s : storage)
			s.setIndicesLiveAfterInit(ImmutableSortedSet.copyOf(Sets.intersection(initWrites.get(s), futureReads.get(s))));
		//Assert we covered the required read indices.
		for (Storage s : storage) {
			if (s.isInternal())
				assert s.indicesLiveDuringSteadyState().isEmpty();
			else
				for (int i : s.readIndices())
					assert s.indicesLiveDuringSteadyState().contains(i) : s + ": " + i + " not in " + s.indicesLiveDuringSteadyState();
		}
	}

	private static final class MigrationInstruction implements Runnable {
		private final ConcreteStorage init, steady;
		private final ImmutableSortedSet<Integer> indicesToMigrate;
		private final int offset;
		private MigrationInstruction(Storage storage, ConcreteStorage init, ConcreteStorage steady) {
			this.init = init;
			this.steady = steady;
			this.indicesToMigrate = storage.indicesLiveAfterInit();
			this.offset = storage.indicesLiveAfterInit().first() - storage.indicesLiveDuringSteadyState().first();
		}
		@Override
		public void run() {
			init.sync();
			for (int i : indicesToMigrate)
				steady.write(i - offset, init.read(i));
			steady.sync();
		}
	}

	/**
	 * The X doesn't stand for anything.  I just needed a different name.
	 */
	private static final class XDrainInstruction implements DrainInstruction {
		private final Token token;
		private final ConcreteStorage storage;
		private final ImmutableSortedSet<Integer> indices;
		private XDrainInstruction(Token token, ConcreteStorage storage, ImmutableSortedSet<Integer> indices) {
			this.token = token;
			this.storage = storage;
			this.indices = indices;
		}
		@Override
		public Map<Token, Object[]> call() {
			Object[] data = new Object[indices.size()];
			for (int i : indices)
				data[i] = storage.read(i);
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

	public static void main(String[] args) {
		StreamCompiler sc = new Compiler2StreamCompiler().multiplier(1000).maxNumCores(8);
//		Benchmark bm = new PipelineSanity.Add15();
		Benchmark bm = new FMRadio.FMRadioBenchmarkProvider().iterator().next();
		Benchmarker.runBenchmark(bm, sc).get(0).print(System.out);
	}
}
