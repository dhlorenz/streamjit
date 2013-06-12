/**
 * @author Sumanan sumanan@mit.edu
 * @since May 27, 2013
 */
package edu.mit.streamjit.impl.distributed.node;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import edu.mit.streamjit.impl.distributed.api.NodeInfo;
import edu.mit.streamjit.impl.distributed.api.RequestProcessor;

public class RequestProcessorImpl implements RequestProcessor {

	StreamNode slave;

	RequestProcessorImpl(StreamNode slave) {
		this.slave = slave;
	}

	@Override
	public void processAPPStatus() {
		System.out.println("APPStatus requested");
	}

	@Override
	public void processSysInfo() {
		System.out.println("SysInfo requested");
	}

	@Override
	public void processMaxCores() {
		try {
			slave.masterConnection.writeObject(new Integer(Runtime.getRuntime().availableProcessors()));
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void processMachineID() {
		try {
			Integer id = slave.masterConnection.readObject();
			slave.setMachineID(id);
		} catch (ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public void processNodeInfo() {
		NodeInfo myInfo = NodeInfo.getMyinfo();
		try {
			slave.masterConnection.writeObject(myInfo);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
