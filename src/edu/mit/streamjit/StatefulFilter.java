package edu.mit.streamjit;

/**
 *
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 11/7/2012
 */
public abstract class StatefulFilter<I, O> extends Filter<I, O> {
	public StatefulFilter(int popRate, int pushRate, int peekRate) {
		super(popRate, pushRate, peekRate);
	}
}