package edu.mit.streamjit.impl.common;

import edu.mit.streamjit.api.Filter;
import edu.mit.streamjit.api.Joiner;
import edu.mit.streamjit.api.OneToOneElement;
import edu.mit.streamjit.api.Pipeline;
import edu.mit.streamjit.api.Splitjoin;
import edu.mit.streamjit.api.Splitter;
import edu.mit.streamjit.api.StreamVisitor;
import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * Prints out a structured stream graph.
 *
 * TODO: this may belong in the api package, as end-users might find it helpful
 * for debugging graph-assembly code.
 * @author Jeffrey Bosboom <jeffreybosboom@gmail.com>
 * @since 7/26/2013
 */
public final class PrintStreamVisitor extends StreamVisitor {
	private static final String INDENTATION = "    ";
	private final PrintWriter writer;
	private int indentLevel = 0;
	public PrintStreamVisitor(PrintWriter writer) {
		this.writer = writer;
	}
	public PrintStreamVisitor(PrintStream stream) {
		this(new PrintWriter(stream));
	}

	@Override
	public void beginVisit() {
	}

	@Override
	public void visitFilter(Filter<?, ?> filter) {
		indent();
		writer.println(filter.toString());
	}

	@Override
	public boolean enterPipeline(Pipeline<?, ?> pipeline) {
		indent();
		writer.println("pipeline " + pipeline.toString() + " {");
		++indentLevel;
		return true;
	}

	@Override
	public void exitPipeline(Pipeline<?, ?> pipeline) {
		--indentLevel;
		indent();
		writer.println("} //end pipeline "+pipeline);
	}

	@Override
	public boolean enterSplitjoin(Splitjoin<?, ?> splitjoin) {
		indent();
		writer.println("splitjoin "+splitjoin.toString()+" {");
		++indentLevel;
		return true;
	}

	@Override
	public void visitSplitter(Splitter<?, ?> splitter) {
		indent();
		writer.println("splitter " + splitter.toString());
	}

	@Override
	public boolean enterSplitjoinBranch(OneToOneElement<?, ?> element) {
		return true;
	}

	@Override
	public void exitSplitjoinBranch(OneToOneElement<?, ?> element) {
	}

	@Override
	public void visitJoiner(Joiner<?, ?> joiner) {
		indent();
		writer.println("joiner "+joiner.toString());
	}

	@Override
	public void exitSplitjoin(Splitjoin<?, ?> splitjoin) {
		--indentLevel;
		indent();
		writer.println("} //end splitjoin "+splitjoin);
	}

	@Override
	public void endVisit() {
		assert indentLevel == 0 : "mismatched indentation: "+indentLevel;
		writer.flush();
	}

	private void indent() {
		for (int i = 0; i < indentLevel; ++i)
			writer.write(INDENTATION);
	}
}
