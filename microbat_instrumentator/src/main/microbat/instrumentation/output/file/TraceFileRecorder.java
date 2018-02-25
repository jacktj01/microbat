package microbat.instrumentation.output.file;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import microbat.instrumentation.AgentUtils;
import microbat.instrumentation.output.TraceOutputWriter;
import microbat.model.trace.Trace;

public class TraceFileRecorder {
	private File file;

	public TraceFileRecorder(String filePath) throws FileNotFoundException {
		file = AgentUtils.getFileCreateIfNotExist(filePath);
	}

	public void writeTrace(String message, Trace trace, final boolean append) throws IOException {
		final FileOutputStream fileStream = new FileOutputStream(file, append);
		// Avoid concurrent writes from other processes:
		fileStream.getChannel().lock();
		final OutputStream bufferedStream = new BufferedOutputStream(fileStream);
		TraceOutputWriter outputWriter = null;
		try {
			outputWriter = new TraceOutputWriter(bufferedStream);
			outputWriter.writeString(message);
			outputWriter.writeTrace(trace);
		} finally {
			bufferedStream.close();
			if (outputWriter != null) {
				outputWriter.close();
			}
		}
	}

}
