/**
 * @author Sumanan sumanan@mit.edu
 * @since May 10, 2013
 */
package edu.mit.streamjit.impl.distributed.runtime.slave;

import java.io.IOException;

import edu.mit.streamjit.impl.distributed.runtime.api.Command;
import edu.mit.streamjit.impl.distributed.runtime.api.MessageElement;
import edu.mit.streamjit.impl.distributed.runtime.api.MessageVisitor;
import edu.mit.streamjit.impl.distributed.runtime.common.GlobalConstants;
import edu.mit.streamjit.impl.distributed.runtime.common.Ipv4Validator;
import edu.mit.streamjit.impl.distributed.runtime.master.Master;

/**
 * This class is driving class at slave side. Once it is started, it will keep on listening and processing the commands from the
 * {@link Master}. {@link Master} can issue the {@link Command} EXIT to stop the slave.
 */
public class Slave {

	SlaveConnection masterConnection;
	private int machineID; // TODO: consider move or remove this from Slave class. If so, this class will be more handy.
	MessageVisitor mv;

	private boolean run; // As we assume that all master communication and the MessageElement processing is managed by single thread,
							// no need to make this variable thread safe.

	public void exit() {
		this.run = false;
	}

	/**
	 * Only IP address is required. PortNo is optional. If it is not provided, {@link Slave} will try to start with default StreamJit's
	 * port number that can be found {@link GlobalConstants}.
	 */
	public Slave(String ipAddress, int portNo) {
		masterConnection = new SlaveTCPConnection(ipAddress, portNo);
		this.mv = new SlaveMessageVisitor(new SlaveAppStatusProcessor(), new SlaveCommandProcessor(this), new SlaveErrorProcessor(),
				new SlaveRequestProcessor(masterConnection), new SlaveJsonStringProcessor(this));
		this.run = true;
	}

	/**
	 * Only IP address is required. PortNo is optional. If it is not provided, {@link Slave} will try to start with default StreamJit's
	 * port number that can be found {@link GlobalConstants}.
	 */
	public Slave(String ipAddress) {
		this(ipAddress, GlobalConstants.PORTNO);
	}

	public void run() {

		try {
			masterConnection.makeConnection();
		} catch (IOException e1) {
			System.out.println("Couldn't extablish the connection with Master node. I am terminating...");
			e1.printStackTrace();
			System.exit(0);
		}

		while (run) {
			try {
				MessageElement me = masterConnection.readObject();
				me.accept(mv);
			} catch (ClassNotFoundException | IOException e) {
				e.printStackTrace();
			}
		}
		releaseResources();
	}

	public int getMachineID() {
		return machineID;
	}

	public void setMachineID(int machineID) {
		this.machineID = machineID;
	}

	// Release all file pointers, opened sockets, etc.
	private void releaseResources() {
		try {
			this.masterConnection.closeConnection();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		int requiredArgCount = 1; // Port no is optional.
		String ipAddress;

		if (args.length < requiredArgCount) {
			System.out.println(args.length);
			System.out.println("Not enough parameters passed. Please provide thr following parameters.");
			System.out.println("0: Master's IP address");
			System.exit(0);
		}

		ipAddress = args[0];
		if (!Ipv4Validator.getInstance().isValid(ipAddress)) {
			System.out.println("Invalid IP address...");
			System.out.println("Please verify the first argument.");

			System.exit(0);
		}

		if (args.length > 1) {
			int portNo;
			try {
				portNo = Integer.parseInt(args[1]);
				new Slave(ipAddress, portNo).run();

			} catch (NumberFormatException ex) {
				System.out.println("Invalid port No...");
				System.out.println("Please verify the second argument.");
				System.exit(0);
			}
		} else
			new Slave(ipAddress).run();
	}
}