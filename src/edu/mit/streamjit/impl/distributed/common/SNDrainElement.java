package edu.mit.streamjit.impl.distributed.common;

import com.google.common.collect.ImmutableMap;

import edu.mit.streamjit.impl.blob.Blob;
import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.blob.DrainData;
import edu.mit.streamjit.impl.distributed.node.StreamNode;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;

/**
 * {@link Controller} and {@link StreamNode} shall communicate all kind of
 * draining information by exchanging DrainElement. </p> All fields of
 * DrainElement are public and final because the purpose of this class is
 * nothing but exchange data between controller and stream node..
 * 
 * @author Sumanan sumanan@mit.edu
 * @since Jul 29, 2013
 */
public abstract class SNDrainElement implements SNMessageElement {
	private static final long serialVersionUID = 1L;

	public abstract void process(SNDrainProcessor dp);

	@Override
	public void accept(SNMessageVisitor visitor) {
		visitor.visit(this);
	}

	/**
	 * {@link StreamNode}s shall send this object to inform {@link Controller}
	 * that draining of a particular blob is done.
	 */
	public static final class Drained extends SNDrainElement {
		private static final long serialVersionUID = 1L;

		/**
		 * Identifies the blob. Since {@link Blob}s do not have an unique
		 * identifier them self, the minimum input token of that blob is used as
		 * identifier.
		 */
		public final Token blobID;

		public Drained(Token blobID) {
			this.blobID = blobID;
		}

		@Override
		public void process(SNDrainProcessor dp) {
			dp.process(this);
		}
	}

	/**
	 * Contains map of {@link DrainData} of drained {@link Blob}s.
	 * {@link StreamNode}s shall send this back to {@link Controller} to submit
	 * the drain data of the blobs after the draining. See {@link DrainData} for
	 * more information.
	 */
	public static final class DrainedDataMap extends SNDrainElement {
		private static final long serialVersionUID = 1L;

		public final ImmutableMap<Token, DrainData> drainData;

		public DrainedDataMap(ImmutableMap<Token, DrainData> drainData) {
			this.drainData = drainData;
		}

		@Override
		public void process(SNDrainProcessor dp) {
			dp.process(this);
		}
	}

	/**
	 * </p> As sub types of the {@link DrainElement} classes, not enums,
	 * overloaded methods in DrainProcessor is enough. Jvm will automatically
	 * dispatch the sub type of DrainElement to correct matching process() here.
	 * We do not need explicit processXXX() functions as it is done for all
	 * enums such as {@link Error}, {@link AppStatus} and {@link Request}.
	 */
	public interface SNDrainProcessor {

		public void process(Drained drained);

		public void process(DrainedDataMap drainedData);
	}
}
