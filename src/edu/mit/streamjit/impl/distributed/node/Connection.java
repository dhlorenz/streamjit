package edu.mit.streamjit.impl.distributed.node;

import java.io.IOException;

import edu.mit.streamjit.impl.distributed.common.ConnectionFactory;
import edu.mit.streamjit.impl.distributed.runtimer.Controller;

/**
 * Communication interface for both {@link StreamNode} and {@link Controller} side. This interface is for an IO connection that is
 * already created, i.e., creating a connections is not handled at here. Consider {@link ConnectionFactory} to create a connection.
 * </p> For the moment, communicates at object granularity level. We may need to add primitive interface functions later.
 * 
 * @author Sumanan sumanan@mit.edu
 * @since May 14, 2013
 */
public interface Connection {

	/**
	 * Read an object from this connection.
	 * 
	 * @return Received object.
	 * @throws IOException
	 * @throws ClassNotFoundException
	 *             If the object received is not the type of T.
	 */
	public <T> T readObject() throws IOException, ClassNotFoundException;

	/**
	 * Write a object to the connection. </p>throws exception if failed. So no return value needed.
	 * 
	 * @throws IOException
	 */
	public void writeObject(Object obj) throws IOException;

	/**
	 * Close the connection. This function is responsible for all kind of resource cleanup. </p>throws exception if failed. So no
	 * return value needed.
	 * 
	 * @throws IOException
	 */
	public void closeConnection() throws IOException;

	/**
	 * Checks whether the connection is still open or not.
	 * 
	 * @return true if the connection is open and valid.
	 */
	public boolean isStillConnected();
}
