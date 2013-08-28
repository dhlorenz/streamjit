package edu.mit.streamjit.test;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import edu.mit.streamjit.api.Input;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.Output;
import edu.mit.streamjit.impl.blob.Buffer;
import java.util.List;

/**
 * A benchmark stream graph.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 8/12/2013
 */
public interface Benchmark {
	/**
	 * Instantiates the benchmark stream graph.  Always returns a new instance.
	 *
	 * Note that the actual graph is often not an Object->Object graph, but
	 * generics suck, so we'll opt-out here.
	 * @return a new instance of the benchmark stream graph
	 */
	public OneToOneElement<Object, Object> instantiate();

	/**
	 * Returns an unmodifiable list of the inputs available for this benchmark.
	 * @return an unmodifiable list of the inputs available for this benchmark
	 */
	public List<Dataset> inputs();

	/**
	 * Returns a human-readable name for this benchmark.
	 * @return a human-readable name for this benchmark
	 */
	@Override
	public String toString();

	/**
	 * A set of data a benchmark can run with.
	 *
	 * This class uses the "wither" (with-er) pattern.  This is useful for
	 * attaching custom outputs to template dataset instances, and allows
	 * expansion with future parameters by providing defaults.
	 */
	public static final class Dataset {
		private final String name;
		private final Input<Object> input;
		/**
		 * Supplies an Input that produces Buffers for a verifying Output
		 * created by the benchmark framework, or supplies null if reference
		 * output is not available.
		 */
		private final Supplier<Input<Object>> output;
		public Dataset(String name, Input<Object> input, Supplier<Input<Object>> output) {
			this.name = name;
			this.input = input;
			this.output = output;
		}
		@SuppressWarnings("unchecked")
		public Dataset(String name, Input<?> input) {
			this.name = name;
			this.input = (Input<Object>)input;
			this.output = Suppliers.ofInstance(null);
		}
		public Input<Object> input() {
			return input;
		}
		public Input<Object> output() {
			return output.get();
		}
		@Override
		public String toString() {
			return name;
		}

		public Dataset withName(String newName) {
			return new Dataset(newName, input, output);
		}
		@SuppressWarnings("unchecked")
		public Dataset withInput(Input<?> input) {
			return new Dataset(name, (Input<Object>)input, output);
		}
		@SuppressWarnings("unchecked")
		public Dataset withOutput(Input<?> output) {
			return new Dataset(name, input, Suppliers.ofInstance((Input<Object>)output));
		}
	}
}
