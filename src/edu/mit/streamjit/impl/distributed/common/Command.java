package edu.mit.streamjit.impl.distributed.common;

import edu.mit.streamjit.impl.blob.Blob.Token;
import edu.mit.streamjit.impl.distributed.node.StreamNode;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;

/**
 * A command can be send by a {@link Controller} to {@link StreamNode} to carry
 * action on the stream blobs.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 17, 2013
 */
public enum Command implements MessageElement {
	/**
	 * Starts the StreamJit Application. Once all blobs are set, Stream nodes
	 * will wait for start command from the controller to start the execution.
	 */
	START {
		@Override
		public void process(CommandProcessor commandProcessor) {
			commandProcessor.processSTART();
		}
	},
	/**
	 * Stops the StreamJit Application. Not the StreamNode. {@link Controller}
	 * can issue this command to stop the execution of the stream application in
	 * exceptional situation such as some other stream node is failed. Execution
	 * of application will be stopped. No draining carried.
	 */
	STOP {
		@Override
		public void process(CommandProcessor commandProcessor) {
			commandProcessor.processSTOP();
		}
	},
	/**
	 * Properly drain the stream blob.
	 */
	DRAIN {
		@Override
		public void process(CommandProcessor commandProcessor) {
			commandProcessor.processDRAIN();
		}
	},
	/**
	 * This command is to ask StreamNode to exit.
	 */
	EXIT {
		@Override
		public void process(CommandProcessor commandProcessor) {
			commandProcessor.processEXIT();
		}
	};

	@Override
	public void accept(MessageVisitor visitor) {
		visitor.visit(this);
	}

	public abstract void process(CommandProcessor commandProcessor);

	/**
	 * Processes the {@link Command}s received from {@link Controller}. Based on
	 * the received command, appropriate function of this interface will be
	 * called.
	 * 
	 * @author Sumanan sumanan@mit.edu
	 * @since May 20, 2013
	 */
	public interface CommandProcessor {

		public void processSTART();

		public void processSTOP();

		public void processDRAIN();

		public void processEXIT();
	}
}