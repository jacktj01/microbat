package microbat.instrumentation;

import microbat.instrumentation.io.TraceOutputWriter;
import microbat.instrumentation.io.file.FileOutputHandler;
import microbat.instrumentation.io.tcp.TcpConnector;
import microbat.instrumentation.trace.data.ExecutionTracer;
import microbat.instrumentation.trace.data.FilterChecker;
import microbat.instrumentation.trace.data.IExecutionTracer;
import microbat.model.trace.StepVariableRelationEntry;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;
import microbat.model.variable.Variable;
import microbat.sql.TraceRecorder;
import sav.strategies.dto.AppJavaClassPath;

public class Agent {
	private AgentParams agentParams;
	private static String programMsg = "";
	
	public Agent(String agentArgs) {
		agentParams = AgentParams.parse(agentArgs);
	}

	public void startup() {
		/* init filter */
		AppJavaClassPath appPath = new AppJavaClassPath();
		appPath.setLaunchClass(agentParams.getLaunchClass());
		appPath.setJavaHome(agentParams.getJavaHome());
		for(String cp: agentParams.getClassPaths()){
			appPath.addClasspath(cp);
		}
		appPath.setWorkingDirectory(agentParams.getWorkingDirectory());
		FilterChecker.setup(appPath);
		ExecutionTracer.appJavaClassPath = appPath;
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				try {
					shutdown();	
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	public void shutdown() throws Exception {
		ExecutionTracer.shutdown();
		/* collect trace & store */
		System.out.println("Recording trace...");
		IExecutionTracer tracer = ExecutionTracer.getMainThreadStore();
	
		Trace trace = ((ExecutionTracer) tracer).getTrace();
		
		createVirtualDataRelation(trace);
		trace.constructControlDomianceRelation();
//		trace.constructLoopParentRelation();
		
		writeOutput(trace);
		System.out.println("Finish recording.");
	}

	private void writeOutput(Trace trace) throws Exception {
		if (agentParams.getDumpFile() != null) {
			FileOutputHandler fileHandler = new FileOutputHandler(agentParams.getDumpFile());
			fileHandler.save(programMsg, trace, false);
		} else if (agentParams.getTcpPort() != -1) {
			TcpConnector tcpConnector = new TcpConnector(agentParams.getTcpPort());
			TraceOutputWriter traceWriter = tcpConnector.connect();
			traceWriter.writeString(programMsg);
			traceWriter.writeTrace(trace);
			traceWriter.flush();
			Thread.sleep(10000l);
			tcpConnector.close();
		} else {
			TraceRecorder traceRecorder = new TraceRecorder();
			traceRecorder.storeTrace(trace );
		}
	}
	
	private void createVirtualDataRelation(Trace trace) {
		for(int i=0; i<trace.size(); i++){
			int order = i+1;
			TraceNode currentNode = trace.getTraceNode(order);
			if(order<trace.size()){
				TraceNode nextNode = trace.getTraceNode(order+1);
				currentNode.setStepInNext(nextNode);
				nextNode.setStepInPrevious(currentNode);
			}
			else if(order==trace.size()){
				TraceNode prevNode = trace.getTraceNode(order-1);
				currentNode.setStepInPrevious(prevNode);
			}
			
			if(currentNode.getInvocationParent()!=null && !currentNode.getPassParameters().isEmpty()){
				TraceNode invocationParent = currentNode.getInvocationParent();
				TraceNode firstChild = invocationParent.getInvocationChildren().get(0);
				if(firstChild.getOrder()==currentNode.getOrder()){
					for(VarValue value: currentNode.getPassParameters()){
						String varID = value.getVarID();
						StepVariableRelationEntry entry = trace.getStepVariableTable().get(varID);
						if(entry==null){
							entry = new StepVariableRelationEntry(varID);
						}
						entry.addProducer(invocationParent);
						trace.getStepVariableTable().put(varID, entry);
					}
				}
			}
			
			if(currentNode.getInvocationParent()!=null && !currentNode.getReturnedVariables().isEmpty()){
				TraceNode invocationParent = currentNode.getInvocationParent();
				TraceNode stepOverNext = invocationParent.getStepOverNext();
				System.currentTimeMillis();
				if(stepOverNext!=null){
					for(VarValue value: currentNode.getReturnedVariables()){
						currentNode.addWrittenVariable(value);
						stepOverNext.addReadVariable(value);
						String varID = value.getVarID();
						String definingOrder = trace.findDefiningNodeOrder(Variable.WRITTEN, currentNode, varID, varID);
						varID = varID+":"+definingOrder;
						value.setVarID(varID);
						StepVariableRelationEntry entry = trace.getStepVariableTable().get(varID);
						if(entry==null){
							entry = new StepVariableRelationEntry(varID);
						}
						entry.addProducer(currentNode);
						entry.addConsumer(stepOverNext);
						trace.getStepVariableTable().put(varID, entry);
					}
				}
			}
		}
	}
	
	public static void setProgramMsg(String programMsg) {
		Agent.programMsg = programMsg;
	}

	public static String extrctJarPath() {
		// TODO Auto-generated method stub
		return null;
	}
	
	public AgentParams getAgentParams() {
		return agentParams;
	}

}