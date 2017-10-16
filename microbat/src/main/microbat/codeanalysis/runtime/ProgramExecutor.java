package microbat.codeanalysis.runtime;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.bcel.Repository;
import org.apache.bcel.util.SyntheticRepository;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdi.TimeoutException;
import org.eclipse.jdi.internal.VoidValueImpl;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.ThreadStartEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.MethodExitRequest;
import com.sun.jdi.request.StepRequest;

import microbat.codeanalysis.ast.LocalVariableScope;
import microbat.codeanalysis.ast.VariableScopeParser;
import microbat.codeanalysis.bytecode.ClassPath0;
import microbat.codeanalysis.bytecode.LineNumberVisitor0;
import microbat.codeanalysis.bytecode.RWVarRetrieverForLine;
import microbat.codeanalysis.runtime.herustic.HeuristicIgnoringFieldRule;
import microbat.codeanalysis.runtime.jpda.expr.ExpressionParser;
import microbat.codeanalysis.runtime.jpda.expr.ParseException;
import microbat.codeanalysis.runtime.variable.VariableValueExtractor;
import microbat.model.BreakPoint;
import microbat.model.BreakPointValue;
import microbat.model.UserInterestedVariables;
import microbat.model.trace.StepVariableRelationEntry;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.ArrayValue;
import microbat.model.value.GraphNode;
import microbat.model.value.PrimitiveValue;
import microbat.model.value.ReferenceValue;
import microbat.model.value.StringValue;
import microbat.model.value.VarValue;
import microbat.model.value.VirtualValue;
import microbat.model.variable.ArrayElementVar;
import microbat.model.variable.FieldVar;
import microbat.model.variable.LocalVar;
import microbat.model.variable.Param;
import microbat.model.variable.Variable;
import microbat.model.variable.VirtualVar;
import microbat.util.BreakpointUtils;
import microbat.util.JavaUtil;
import microbat.util.MicroBatUtil;
import microbat.util.PrimitiveUtils;
import microbat.util.Settings;
import sav.common.core.SavException;
import sav.common.core.utils.CollectionUtils;
import sav.common.core.utils.SignatureUtils;
import sav.strategies.dto.AppJavaClassPath;

/**
 * @author Yun Lin
 * 
 *         This class origins from three classes written by LLT, i.e.,
 *         BreakpointDebugger, JunitDebugger, and TestcaseExecutor.
 * 
 */
@SuppressWarnings("restriction")
public class ProgramExecutor extends Executor {
	public static final long DEFAULT_TIMEOUT = -1;

	/**
	 * fundamental fields for debugging
	 */
	/**
	 * the class patterns indicating the classes into which I will not step to
	 * get the runtime values
	 */
	private AppJavaClassPath appPath;

	/** maps from a given class name to its contained breakpoints */
	private Map<String, List<BreakPoint>> brkpsMap;

	/**
	 * for recording execution trace
	 */
	private Trace trace;

	public ProgramExecutor() {
	}

	/**
	 * Executing the program, each time of the execution, we catch a JVM event
	 * (e.g., step event, class preparing event, method entry event, etc.).
	 * Generally, we collect the runtime program states in some interesting step
	 * event, and record these steps and their corresponding program states in a
	 * trace node.
	 * 
	 * <br>
	 * <br>
	 * Note that the trace node can form a tree-structure in terms of method
	 * invocation relations and loop relations.
	 * 
	 * <br>
	 * <br>
	 * See the field <code>trace</code> in this class.
	 * 
	 * <br>
	 * The parameter of executionOrderList is used to make sure the trace is
	 * recorded as executionOrderList shows. Different from when we record the
	 * executionOrderList, we will listen to method entry/exist event, which can
	 * introduces some steps which executionOrderList does not record. In such
	 * case, we just focus on the part of trace recorded by executionOrderList.
	 * 
	 * @param runningStatements
	 * @throws SavException
	 */
	public void run(List<BreakPoint> runningStatements, List<BreakPoint> executionOrderList, IProgressMonitor monitor,
			int stepNum, boolean isTestcaseEvaluation) throws SavException, TimeoutException {
		Repository.clearCache();
		this.trace = new Trace(appPath);

		List<String> classScope = parseScope(runningStatements);
		List<LocalVariableScope> lvsList = parseLocalVariables(classScope, this.appPath);
		this.trace.getLocalVariableScopes().setVariableScopes(lvsList);

		List<String> exlcudes = MicroBatUtil.extractExcludeFiles("", appPath.getExternalLibPaths());
		this.addLibExcludeList(exlcudes);
		this.brkpsMap = BreakpointUtils.initBrkpsMap(runningStatements);

		List<PointWrapper> wrapperList = convertToPointWrapperList(executionOrderList);

		VirtualMachine vm = null;
		try {
			vm = constructTrace(monitor, wrapperList, this.appPath, stepNum, isTestcaseEvaluation);
		} finally {
			if (vm != null) {
				vm.exit(0);
			}
			System.out.println();
			System.out.println("JVM is ended.");
		}

	}

	/**
	 * This method is used to build the scope of local variables.
	 * 
	 * @param classScope
	 */
	private List<LocalVariableScope> parseLocalVariables(final List<String> classScope, AppJavaClassPath appPath) {
		VariableScopeParser vsParser = new VariableScopeParser();
		vsParser.parseLocalVariableScopes(classScope, appPath);
		List<LocalVariableScope> lvsList = vsParser.getVariableScopeList();
		return lvsList;
	}

	private List<String> parseScope(List<BreakPoint> breakpoints) {
		List<String> classes = new ArrayList<>();
		for (BreakPoint bp : breakpoints) {
			if (!classes.contains(bp.getDeclaringCompilationUnitName())) {
				classes.add(bp.getDeclaringCompilationUnitName());
			}
		}
		return classes;
	}

	private List<PointWrapper> convertToPointWrapperList(List<BreakPoint> executionOrderList) {
		List<PointWrapper> list = new ArrayList<>();
		for (BreakPoint point : executionOrderList) {
			PointWrapper pWrapper = new PointWrapper(point);
			list.add(pWrapper);
		}
		return list;
	}

	class PointWrapper {
		BreakPoint point;
		boolean isHit = false;

		public PointWrapper(BreakPoint point) {
			this.point = point;
		}

		public BreakPoint getPoint() {
			return point;
		}

		public void setPoint(BreakPoint point) {
			this.point = point;
		}

		public boolean isHit() {
			return isHit;
		}

		public void setHit(boolean isHit) {
			this.isHit = isHit;
		}

		@Override
		public String toString() {
			return "PointWrapper [point=" + point + ", isHit=" + isHit + "]";
		}
	}
	
	class UsedVariables{
		List<VarValue> readVariables = new ArrayList<>();
		List<VarValue> writtenVariables = new ArrayList<>();
		
		Value returnedValue;
		
		public UsedVariables(List<VarValue> readVariables, List<VarValue> writtenVariables, Value returnedValue) {
			super();
			this.readVariables = readVariables;
			this.writtenVariables = writtenVariables;
			this.returnedValue = returnedValue;
		}
	}

	/**
	 * The parameter of executionOrderList is used to make sure the trace is
	 * recorded as executionOrderList shows. Different from when we record the
	 * executionOrderList, we will listen to method entry/exist event, which can
	 * introduces some steps which executionOrderList does not record. In such
	 * case, we just focus on the part of trace recorded by executionOrderList.
	 * 
	 * @param monitor
	 * @param executionOrderList
	 * @param appClassPath
	 * @param vm
	 * @param stepNum
	 * @param isTestcaseEvaluation
	 * @throws SavException
	 * @throws TimeoutException
	 */
	private VirtualMachine constructTrace(IProgressMonitor monitor, List<PointWrapper> executionOrderList,
			AppJavaClassPath appClassPath, int stepNum, boolean isTestcaseEvaluation)
			throws SavException, TimeoutException {

		/** start debugger */
		VirtualMachine vm = new VMStarter(this.appPath, isTestcaseEvaluation).start();

		EventRequestManager erm = vm.eventRequestManager();

		/** add class watch, otherwise, I cannot catch the registered event */
		addClassWatch(erm);

		EventQueue eventQueue = vm.eventQueue();

		boolean stop = false;
		boolean eventTimeout = false;
		Map<String, BreakPoint> locBrpMap = new HashMap<String, BreakPoint>();

		/**
		 * This variable aims to record the last executed stepping point. If
		 * this variable is not null, then the next time we listen a step event,
		 * the values collected then are considered the aftermath of latest
		 * recorded trace node.
		 */
		BreakPoint lastSteppingInPoint = null;

		/**
		 * We support recoding the trace in two modes: normal mode and test case
		 * mode. When in test case mode, a lot of method invocation from JUnit
		 * framework is useless, so I need to skip some events from JUnit by
		 * this variable.
		 */
		boolean isInRecording = false;

		boolean isRecoverMethodRequest = false;

		/**
		 * record the method entrance and exit so that I can build a
		 * tree-structure for trace node.
		 */
		Stack<TraceNode> methodNodeStack = new Stack<>();
		// Stack<Method> methodStack = new Stack<>();
		Stack<String> methodSignatureStack = new Stack<>();

		/**
		 * this variable is used to build step-over relation between trace
		 * nodes.
		 */
		TraceNode methodNodeJustPopedOut = null;
		Value lastestReturnedValue = null;

		/** this variable is used to handle exception case. */
		Location caughtLocationForJustException = null;

		cancel: while (!stop && !eventTimeout) {
			EventSet eventSet;
			try {
				eventSet = eventQueue.remove(TIME_OUT);
				if (isRecoverMethodRequest) {
					this.methodEntryRequest.enable();
					this.methodExitRequest.enable();
					isRecoverMethodRequest = false;
				}
			} catch (Exception e) {
				e.printStackTrace();
				break;
			}
			if (eventSet == null) {
				System.out.println("Time out! Cannot get event set!");
				eventTimeout = true;
				break;
			}

			if (trace.getLastestNode() != null) {
				// System.out.println("running into " + trace.getLastestNode());
				// System.currentTimeMillis();
			}

			/**
			 * ensure the step event is parsed before the method entry event
			 */
			List<Event> sortedEvents = sortEvents(eventSet);
			
			for (Event event : sortedEvents) {
				if (event instanceof VMStartEvent) {
					System.out.println("JVM is started...");

					ThreadReference thread = ((VMStartEvent) event).thread();
					addStepWatch(erm, thread);
					addMethodWatch(erm);
					addExceptionWatch(erm);
					addThreadStartWatch(erm);

					if (isTestcaseEvaluation) {
						disableAllStepRequests();
					} else {
						excludeJUnitLibs();
					}

				} else if (event instanceof ThreadStartEvent) {
					ThreadReference threadReference = ((ThreadStartEvent) event).thread();
					if (hasValidThreadName(threadReference)) {
						// addStepWatch(erm, threadReference);
						// excludeJUnitLibs();
						// System.currentTimeMillis();
					}
				}
				if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
					stop = true;
					break;
				} else if (event instanceof ClassPrepareEvent) {
					parseBreakpoints(vm, (ClassPrepareEvent) event, locBrpMap);
				} else if (event instanceof StepEvent) {
					ThreadReference thread = ((StepEvent) event).thread();
					Location currentLocation = ((StepEvent) event).location();

					if(isInIncludedLibrary(currentLocation)){
						if(trace.size()>0){
							Value returnValue = build3rdPartyLibraryDependency(thread, currentLocation);
//							returnValue = build3rdPartyLibraryDependency(thread, currentLocation);
							if(returnValue != null){
								lastestReturnedValue = returnValue;
							}
						}
					}
					
					if (currentLocation.lineNumber() == -1) {
						continue;
					}

					// System.out.println(currentLocation);

					this.methodEntryRequest.setEnabled(true);
					this.methodExitRequest.setEnabled(true);

					/**
					 * collect the variable values after executing previous step
					 */
					boolean isContextChange = false;
					if (lastSteppingInPoint != null) {
						// collectValueOfPreviousStep(lastSteppingInPoint,
						// thread, currentLocation);

						/**
						 * Parsing the written variables of last step.
						 * 
						 * If the context changes, the value of some variables
						 * may not be retrieved. Thus, the variable ID cannot be
						 * generated. Note that the ID of a variable need
						 * parsing its heap ID which can only be accessed by
						 * runtime.
						 */
						isContextChange = checkContext(lastSteppingInPoint, currentLocation);
						if (!isContextChange) {
							parseReadWrittenVariableInThisStep(thread, currentLocation, this.trace.getLastestNode(),
									this.trace.getStepVariableTable(), Variable.WRITTEN);
						}

						lastSteppingInPoint = null;
					}

					BreakPoint bkp = locBrpMap.get(currentLocation.toString());
					/**
					 * This step is an interesting step (sliced statement) in
					 * our debugging process
					 */
					if (bkp != null /* && bkp.equals(supposedBkp) */) {
						BreakPointValue bkpVal = null;
						TraceNode node = recordTrace(bkp, bkpVal);

						/**
						 * pop up method after an exception is caught.
						 */
						if (caughtLocationForJustException != null) {
							methodNodeJustPopedOut = popUpMethodCausedByException(methodNodeStack, methodSignatureStack,
									methodNodeJustPopedOut, caughtLocationForJustException);
							caughtLocationForJustException = null;
						}

						/**
						 * Build parent-child relation between trace nodes.
						 */
						if (!methodNodeStack.isEmpty()) {
							TraceNode parentInvocationNode = methodNodeStack.peek();
							parentInvocationNode.addInvocationChild(node);
							node.setInvocationParent(parentInvocationNode);
						}

						/**
						 * set step over previous/next node when this step just
						 * come back from a method invocation ( i.e.,
						 * lastestPopedOutMethodNode != null).
						 */
						Value returnedValue = lastestReturnedValue;
						if (node != null && methodNodeJustPopedOut != null) {
							methodNodeJustPopedOut.setStepOverNext(node);
							methodNodeJustPopedOut.setAfterStepOverState(node.getProgramState());

							node.setStepOverPrevious(methodNodeJustPopedOut);

							methodNodeJustPopedOut = null;
							returnedValue = lastestReturnedValue;
						}

						parseReadWrittenVariableInThisStep(thread, currentLocation, node,
								this.trace.getStepVariableTable(), Variable.READ);
						/**
						 * create virtual variable for return statement
						 */
						if (this.trace.size() > 1) {
							TraceNode lastestNode = this.trace.getExectionList().get(this.trace.size() - 2);
							if (lastestNode.getBreakPoint().isReturnStatement()) {
								createVirutalVariableForReturnStatement(thread, node, lastestNode, returnedValue);
							}
						}

						lastSteppingInPoint = bkp;

						monitor.worked(1);
						printProgress(trace.size(), stepNum);
					}

					if (monitor.isCanceled() || this.trace.getExectionList().size() >= Settings.stepLimit) {
						stop = true;
						break cancel;
					}
				} else if (event instanceof MethodEntryEvent) {
					MethodEntryEvent mee = (MethodEntryEvent) event;
					Method method = mee.method();
					// System.out.println("enter " + method + ":" +
					// ((MethodEntryEvent)event).location());

					if(isInIncludedLibrary(method.location())){
						continue;
					}
					
					/**
					 * See the explanation of isInRcording variable.
					 */
					if (isTestcaseEvaluation && !isInRecording) {
						String declaringTypeName = method.declaringType().name();
						if (isTagJUnitCall(declaringTypeName, method.name())) {
							enableAllStepRequests();
							isInRecording = true;
							excludeJUnitLibs();
						} else {
							continue;
						}
					}

					Location location = ((MethodEntryEvent) event).location();
					PointWrapper nextPoint = getNextPoint(executionOrderList);
					if (isInterestedMethod(location, nextPoint)) {
						nextPoint.setHit(true);
						TraceNode lastestNode = this.trace.getLastestNode();
						if (lastestNode != null) {
							try {
								if (!method.arguments().isEmpty()) {
									StackFrame frame = findFrame(((MethodEntryEvent) event).thread(), mee.location());
									String path = location.sourcePath();
									String declaringCompilationUnit = path.replace(".java", "");
									declaringCompilationUnit = declaringCompilationUnit.replace(File.separatorChar,
											'.');

									int methodLocationLine = method.location().lineNumber();
									List<Param> paramList = parseParamList(method);

									parseWrittenParameterVariableForMethodInvocation(frame, declaringCompilationUnit,
											methodLocationLine, paramList, lastestNode);
								}
							} catch (AbsentInformationException e) {
								e.printStackTrace();
							}

							methodNodeStack.push(lastestNode);
							String methodSignature = createSignature(method);
							methodSignatureStack.push(methodSignature);
						}

						System.currentTimeMillis();
					} else {
						/**
						 * It check whether a \<clint\> method is visited in
						 * previous method entry event. If yes, this variable
						 * will be set true. The reason is to prevent JVM from
						 * being hanged. We observe that calling a \<clint\>
						 * method sometimes hang the JVM, causing a JVM timeout
						 * exception. Therefore, once we meet such a method, we
						 * try to skip.
						 */
						if (nextPoint != null) {
							if (nextPoint.isHit || method.name().equals("<clinit>")
									|| isInSameMethod(trace.getLastestNode(), nextPoint)) {
								this.methodEntryRequest.setEnabled(false);
								this.methodExitRequest.setEnabled(false);

								if (method.name().equals("<clinit>")) {
									isRecoverMethodRequest = true;
								}
							}
						}
					}

				} else if (event instanceof MethodExitEvent) {
					MethodExitEvent mee = (MethodExitEvent) event;
					Method method = mee.method();
					// System.out.println("exit " + method + ":" +
					// ((MethodExitEvent)event).location());
					
					if(isInIncludedLibrary(method.location())){
						continue;
					}

					PointWrapper lastPoint = findCorrespondingPointWrapper(this.trace.getLastestNode(),
							executionOrderList);
					// if(isInterestedMethod(method, this.brkpsMap)){
					if (isInterestedMethod(((MethodExitEvent) event).location(), lastPoint)) {
						lastPoint.setHit(true);
						String thisSig = createSignature(method);
						if (!methodSignatureStack.isEmpty()) {
							String peekSig = methodSignatureStack.peek();
							// if (JavaUtil.isCompatibleMethodSignature(peekSig,
							// thisSig)) {
							if (peekSig.equals(thisSig)) {
								TraceNode node = methodNodeStack.pop();
								methodNodeJustPopedOut = node;
								methodSignatureStack.pop();
								lastestReturnedValue = mee.returnValue();
							} else {
								int index = -1;
								for (int i = methodSignatureStack.size() - 1; i >= 0; i--) {
									String sig = methodSignatureStack.get(i);
									if (sig.equals(thisSig)) {
										index = i;
										break;
									}
								}

								if (index != -1) {
									int popNum = methodSignatureStack.size() - index;
									for (int i = 0; i < popNum; i++) {
										TraceNode node = methodNodeStack.pop();
										methodNodeJustPopedOut = node;
										methodSignatureStack.pop();
										lastestReturnedValue = mee.returnValue();
									}
								}
							}
						}
					} else {
						if (lastPoint != null && lastPoint.isHit) {
							this.methodEntryRequest.setEnabled(false);
							this.methodExitRequest.setEnabled(false);
						}
					}

				} else if (event instanceof ExceptionEvent) {
					ExceptionEvent ee = (ExceptionEvent) event;
					Location catchLocation = ee.catchLocation();
					TraceNode lastNode = this.trace.getLastestNode();
					if (lastNode != null) {
						lastNode.setException(true);

						if (catchLocation == null) {
							stop = true;
						} else {
							caughtLocationForJustException = ee.catchLocation();
						}
					}
				} else if (event instanceof BreakpointEvent) {
					System.currentTimeMillis();
				}
			}

			eventSet.resume();
		}

		return vm;
	}

	private Value build3rdPartyLibraryDependency(ThreadReference thread, Location currentLocation) {
		UsedVariables uVars = parseUsedVariable(thread, currentLocation);
		
		for(VarValue readVar: uVars.readVariables){
			StepVariableRelationEntry entry = trace.getStepVariableTable().get(readVar.getVarID());
			if(entry == null){
				entry = new StepVariableRelationEntry(readVar.getVarID());
			}
			entry.addConsumer(trace.getLastestNode());
		}
		
		for(VarValue writtenVar: uVars.writtenVariables){
			StepVariableRelationEntry entry = trace.getStepVariableTable().get(writtenVar.getVarID());
			if(entry == null){
				entry = new StepVariableRelationEntry(writtenVar.getVarID());
			}
			entry.addProducer(trace.getLastestNode());
		}
		
		return uVars.returnedValue;
	}
	
	private HashMap<String, UsedVariables> libraryLine2VariableMap = new HashMap<>();

	private UsedVariables parseUsedVariable(ThreadReference thread, Location currentLocation) {
		int lineNumber = currentLocation.lineNumber();
		String className = currentLocation.declaringType().name();
		int offset = (int)currentLocation.codeIndex();
		
		String locationID = className + "$" + lineNumber + "$" + offset;
		UsedVariables uVars = libraryLine2VariableMap.get(locationID);
//		UsedVariables uVars = null;
		if(uVars == null){
			LineNumberVisitor0 visitor = RWVarRetrieverForLine.parse(className, lineNumber, 
					offset, appPath);
			List<Variable> readVars = visitor.getReadVars();
			List<Variable> writtenVars = visitor.getWrittenVars();
			
			List<VarValue> readVarValues = parseValue(readVars, className, thread, Variable.READ);
			List<VarValue> writtenVarValues = parseValue(writtenVars, className, thread, Variable.WRITTEN);
			
			Value returnedValue = parseValue(visitor.getReturnedVar(), thread);
			
			uVars = new UsedVariables(readVarValues, writtenVarValues, returnedValue);
			libraryLine2VariableMap.put(locationID, uVars);
		}
		
		return uVars;
	}

	private Value parseValue(Variable returnedVar, ThreadReference thread) {
		if(returnedVar==null){
			return null;
		}
		
		try {
			StackFrame frame = thread.frame(0);
			ExpressionValue expValue = retriveExpression(frame, returnedVar.getName(), null);
			if(expValue==null){
				return null;
			}
			return expValue.value;
		} catch (IncompatibleThreadStateException e) {
			e.printStackTrace();
		}
		
		return null;
	}

	private List<VarValue> parseValue(List<Variable> vars, String className, ThreadReference thread, String accessType) {
		List<VarValue> values = new ArrayList<>();
		
		try {
			StackFrame frame = thread.frame(0);
			for(Variable var: vars){
				if(var instanceof FieldVar){
					ExpressionValue expValue = retriveExpression(frame, var.getName(), null);
					
					if(expValue==null){
						continue;
					}
					
					Value parentValue = expValue.value;
					String varID = null;
					if(parentValue != null){
						if (parentValue instanceof ObjectReference) {
							ObjectReference ref = (ObjectReference)parentValue;
							varID = Variable.concanateFieldVarID(String.valueOf(ref.uniqueID()), var.getName());
						}
					}
					else{
						varID = Variable.concanateFieldVarID(className, var.getName());
					}
					
					if(varID != null){
						varID = trace.findDefiningNodeOrder(accessType, trace.getLastestNode(), varID);
						VarValue varValue = new PrimitiveValue(null, false, var);
						values.add(varValue);
					}
				}
				else if(var instanceof ArrayElementVar){
//					String varName = var.getName().substring(0, var.getName().indexOf("["));
					ExpressionValue expValue = retriveExpression(frame, var.getName(), null);
					
					if(expValue==null){
						continue;
					}
					
					Value val = expValue.value;
					ArrayReference ref = (ArrayReference)val;
					List<Value> subValues = ref.getValues();
					int count = 0;
					for(Value sv: subValues){
						String varID = Variable.concanateArrayElementVarID(
								String.valueOf(ref.uniqueID()), String.valueOf(count++));
						varID = trace.findDefiningNodeOrder(accessType, trace.getLastestNode(), varID);
						VarValue varValue = new PrimitiveValue(null, false, var);
						values.add(varValue);
					}
					
				}
				else if(var instanceof LocalVar){
					VarValue localVar = new PrimitiveValue(var.getName(), false, var);
					values.add(localVar);
				}
			}
		} catch (IncompatibleThreadStateException e) {
			e.printStackTrace();
		}
		
		return values;
	}

	private List<Event> sortEvents(EventSet eventSet) {
		Event[] events = eventSet.toArray(new Event[0]);
		List<Event> list = new ArrayList<>();
		for(Event e: events){
			list.add(e);
		}
		
		Collections.sort(list, new Comparator<Event>() {

			@Override
			public int compare(Event o1, Event o2) {
				int score1 = getScore(o1);
				int score2 = getScore(o2);
				return score2-score1;
			}

			private int getScore(Event o) {
				if(o instanceof StepEvent){
					return -1;
				}
				else if((o instanceof MethodEntryEvent) || (o instanceof MethodExitEvent)){
					return 1;
				}
				
				return 0;
			}
		});
		
		return list;
	}

	private TraceNode popUpMethodCausedByException(Stack<TraceNode> methodNodeStack, Stack<String> methodSignatureStack,
			TraceNode methodNodeJustPopedOut, Location caughtLocationForJustException) {
		if (!methodNodeStack.isEmpty()) {
			TraceNode invocationNode = this.trace.findLastestExceptionNode();
			boolean isInvocationEnvironmentContainingLocation = isInvocationEnvironmentContainingLocation(
					invocationNode, caughtLocationForJustException);
			while (!isInvocationEnvironmentContainingLocation) {
				if (!methodNodeStack.isEmpty()) {
					invocationNode = methodNodeStack.pop();
					methodNodeJustPopedOut = invocationNode;
					methodSignatureStack.pop();

					isInvocationEnvironmentContainingLocation = isInvocationEnvironmentContainingLocation(
							invocationNode, caughtLocationForJustException);
				} else {
					break;
				}
			}
		}
		return methodNodeJustPopedOut;
	}

	private boolean isInSameMethod(TraceNode lastestNode, PointWrapper wrapper) {
		if (lastestNode == null || wrapper == null) {
			return false;
		}

		BreakPoint point = wrapper.getPoint();

		String latestMethod = lastestNode.getBreakPoint().getMethodSign();
		String pointMethod = point.getMethodSign();

		if (latestMethod == null && pointMethod == null) {
			return lastestNode.getBreakPoint().getClassCanonicalName().equals(point.getClassCanonicalName());
		} else if (latestMethod != null && pointMethod != null) {
			return lastestNode.getBreakPoint().getMethodSign().equals(point.getMethodSign());
		}

		return false;
	}

	private PointWrapper findCorrespondingPointWrapper(TraceNode lastestNode, List<PointWrapper> executionOrderList) {
		if (lastestNode == null) {
			return null;
		}

		if (lastestNode.getOrder() > executionOrderList.size()) {
			return null;
		}

		return executionOrderList.get(lastestNode.getOrder() - 1);
	}

	private PointWrapper getNextPoint(List<PointWrapper> executionOrderList) {
		int index = trace.getExectionList().size();
		if (index >= executionOrderList.size()) {
			return null;
		}

		return executionOrderList.get(index);
	}

	private boolean isInterestedMethod(Location location, PointWrapper lastSteppingInPoint) {

		if (lastSteppingInPoint != null) {
			if (location.declaringType().toString().equals(lastSteppingInPoint.getPoint().getClassCanonicalName())
					&& location.lineNumber() == lastSteppingInPoint.getPoint().getLineNumber()) {
				return true;
			}
		}

		return false;
	}

	// private boolean isInterestedMethod(Method method, Map<String,
	// List<BreakPoint>> brkpsMap) {
	// String className = method.declaringType().name();
	// List<BreakPoint> recordedLines = brkpsMap.get(className);
	//
	// if(recordedLines!=null && !recordedLines.isEmpty()){
	// try {
	// List<Location> methodLocations = method.allLineLocations();
	//
	// for(Location location: methodLocations){
	// int locationLine = location.lineNumber();
	//
	// for(BreakPoint point: recordedLines){
	// if(point.getLineNumber()==locationLine){
	// return true;
	// }
	// }
	// }
	// } catch (AbsentInformationException e) {
	// e.printStackTrace();
	// }
	// }
	//
	// return false;
	// }

	private void printProgress(int size, int stepNum) {
		double progress = ((double) size) / stepNum;

		double preProgr = 0;
		if (size == 1) {
			System.out.print("progress: ");
		} else {
			preProgr = ((double) (size - 1)) / stepNum;
		}

		int prog = (int) (progress * 100);
		int preP = (int) (preProgr * 100);

		int diff = prog - preP;
		StringBuffer buffer = new StringBuffer();
		for (int i = 0; i < diff; i++) {
			buffer.append("=");
		}
		System.out.print(buffer.toString());

		int[] percentiles = { 10, 20, 30, 40, 50, 60, 70, 80, 90 };
		for (int i = 0; i < percentiles.length; i++) {
			int percentile = percentiles[i];
			if (preP < percentile && percentile <= prog) {
				System.out.print(prog + "%");
			}
		}
	}

	public String trimGenericType(String complexInvokedMethodSig) {
		String simpleSig = complexInvokedMethodSig.replaceAll("<[^<|^>]*>", "");
		return simpleSig;
	}

	public List<Param> findParamList(MethodDeclaration invokedMethod) {
		List<Param> paramList = new ArrayList<>();
		for (Object obj : invokedMethod.parameters()) {
			if (obj instanceof SingleVariableDeclaration) {
				SingleVariableDeclaration svd = (SingleVariableDeclaration) obj;
				String paramName = svd.getName().getIdentifier();
				String paramType = svd.getType().toString();

				Param param = new Param(paramType, paramName);
				paramList.add(param);
			}
		}

		return paramList;
	}

	private List<Param> parseParamList(Method method) {
		List<Param> paramList = new ArrayList<>();

		try {
			for (LocalVariable variable : method.arguments()) {
				Param param = new Param(variable.typeName(), variable.name());
				paramList.add(param);
			}
		} catch (AbsentInformationException e) {
			e.printStackTrace();
		}

		return paramList;
	}

	private String createSignature(Method method) {
		String className = method.declaringType().name();
		String methodName = method.name();
		String methodSig = method.signature();

		String sig = JavaUtil.createSignature(className, methodName, methodSig);

		return sig;
	}

	private boolean isLocationInRunningStatement(Location location, Map<String, BreakPoint> locationMap) {
		String key = location.toString();
		return locationMap.containsKey(key);
	}

	class MethodFinder extends ASTVisitor {
		CompilationUnit cu;
		int lineNumber;

		MethodDeclaration candidate;

		public MethodFinder(CompilationUnit cu, int lineNumber) {
			this.cu = cu;
			this.lineNumber = lineNumber;
		}

		public boolean visit(MethodDeclaration md) {
			int startLine = cu.getLineNumber(md.getStartPosition());
			int endLine = cu.getLineNumber(md.getStartPosition() + md.getLength());

			if (startLine <= lineNumber && endLine >= lineNumber) {
				if (candidate == null) {
					candidate = md;
				} else {
					int candStartLine = cu.getLineNumber(candidate.getStartPosition());
					int candEndLine = cu.getLineNumber(candidate.getStartPosition() + candidate.getLength());

					if (startLine >= candStartLine && endLine <= candEndLine) {
						candidate = md;
					}
				}
			}

			return true;
		}
	}

	private boolean isInvocationEnvironmentContainingLocation(TraceNode methodNode,
			Location caughtLocationForJustException) {
		String qualifiedName = methodNode.getBreakPoint().getDeclaringCompilationUnitName();
		int lineNumber = methodNode.getLineNumber();

		try {
			String path = caughtLocationForJustException.sourcePath();
			path = path.substring(0, path.indexOf(".java"));
			path = path.replace(File.separatorChar, '.');

			if (qualifiedName.equals(path)) {
				CompilationUnit cu = JavaUtil.findCompilationUnitInProject(qualifiedName, appPath);
				if (cu != null) {
					MethodFinder mFinder = new MethodFinder(cu, lineNumber);
					cu.accept(mFinder);
					MethodDeclaration md = mFinder.candidate;

					if (md != null) {
						int mdStartLine = cu.getLineNumber(md.getStartPosition());
						int mdEndLine = cu.getLineNumber(md.getStartPosition() + md.getLength());

						int caughtLine = caughtLocationForJustException.lineNumber();
						if (caughtLine >= mdStartLine && caughtLine <= mdEndLine) {
							return true;
						}
					}
				}

			}
		} catch (AbsentInformationException e) {
			e.printStackTrace();
		}

		return false;
	}

	/**
	 * when the last interesting stepping statement is a return statement,
	 * create a virtual variable.
	 */
	private void createVirutalVariableForReturnStatement(ThreadReference thread, TraceNode node, TraceNode returnNode,
			Value returnedValue) {

		if (returnedValue instanceof VoidValueImpl) {
			return;
		}

		String returnedType;
		String returnedStringValue;
		if (returnedValue == null) {
			returnedType = VirtualVar.VIRTUAL_TYPE;
			returnedStringValue = "null";
		} else {
			String type = returnedValue.type().toString();

			if (type.contains(".")) {
				type = type.substring(type.lastIndexOf(".") + 1, type.length());
			}

			returnedType = type;
			returnedStringValue = returnedValue.toString();
			if (returnedValue instanceof StringReference) {
				returnedStringValue = returnedStringValue.substring(1, returnedStringValue.length() - 1);
			} else if (returnedValue instanceof ArrayReference) {
				returnedStringValue = JavaUtil.retrieveStringValueOfArray((ArrayReference) returnedValue);
			} else if (returnedValue instanceof ObjectReference) {
				// returnedStringValue = JavaUtil.retrieveToStringValue(thread,
				// (ObjectReference) returnedValue, this);
				returnedStringValue = JavaUtil.retrieveToStringValue((ObjectReference) returnedValue,
						Settings.getVariableLayer(), thread);
			}

		}

		String virID = VirtualVar.VIRTUAL_PREFIX + returnNode.getOrder();

		String virName = VirtualVar.VIRTUAL_PREFIX + getDeclaringMethod(returnNode);

		VirtualVar var = new VirtualVar(virName, returnedType);
		var.setVarID(virID);

		Map<String, StepVariableRelationEntry> map = this.trace.getStepVariableTable();
		StepVariableRelationEntry entry = new StepVariableRelationEntry(var.getVarID());
		entry.addAliasVariable(var);
		entry.addProducer(returnNode);
		entry.addConsumer(node);

		VarValue varValue = new VirtualValue(false, var);
		// String stringValue = "(return from " +
		// lastestNode.getBreakPoint().getMethodName() + "(...))";
		varValue.setStringValue(returnedStringValue);

		returnNode.addWrittenVariable(varValue);
		node.addReadVariable(varValue);

		map.put(var.getVarID(), entry);
	}

	class MethodNameRetriever extends ASTVisitor {
		MethodDeclaration innerMostMethod = null;

		CompilationUnit cu;
		int lineNumber;

		public MethodNameRetriever(CompilationUnit cu, int lineNumber) {
			this.cu = cu;
			this.lineNumber = lineNumber;
		}

		public boolean visit(MethodDeclaration md) {
			int start = cu.getLineNumber(md.getStartPosition());
			int end = cu.getLineNumber(md.getStartPosition() + md.getLength());

			if (start <= lineNumber && lineNumber <= end) {
				if (innerMostMethod == null) {
					innerMostMethod = md;
				} else {
					if (isMoreInner(md, innerMostMethod)) {
						innerMostMethod = md;
					}
				}
				return true;
			} else {
				return false;
			}
		}

		private boolean isMoreInner(MethodDeclaration oldMD, MethodDeclaration newMD) {
			int oldStart = cu.getLineNumber(oldMD.getStartPosition());
			int oldEnd = cu.getLineNumber(oldMD.getStartPosition() + oldMD.getLength());

			int newStart = cu.getLineNumber(newMD.getStartPosition());
			int newEnd = cu.getLineNumber(newMD.getStartPosition() + newMD.getLength());

			if (oldStart <= newStart && newEnd <= oldEnd) {
				return true;
			} else {
				return false;
			}
		}
	}

	private String getDeclaringMethod(TraceNode returnNode) {
		int lineNumber = returnNode.getLineNumber();
		String compilationUnitName = returnNode.getDeclaringCompilationUnitName();
		final CompilationUnit cu = JavaUtil.findCompilationUnitInProject(compilationUnitName, appPath);
		MethodNameRetriever retriever = new MethodNameRetriever(cu, lineNumber);
		cu.accept(retriever);

		MethodDeclaration md = retriever.innerMostMethod;

		if (md != null) {
			String methodName = md.getName().getIdentifier();
			return methodName;
		} else {
			return compilationUnitName.substring(compilationUnitName.lastIndexOf(".") + 1,
					compilationUnitName.length());
		}
	}

	/**
	 * build the written relations between method invocation
	 */
	private void parseWrittenParameterVariableForMethodInvocation(StackFrame frame,
			String methodDeclaringCompilationUnit, int methodLocationLine, List<Param> paramList,
			TraceNode lastestNode) {

		for (Param param : paramList) {

			if (frame == null) {
				return;
			}

			Value value = JavaUtil.retriveExpression(frame, param.getName());
			LocalVar localVar = new LocalVar(param.getName(), param.getType(),
					lastestNode.getDeclaringCompilationUnitName(), lastestNode.getLineNumber());

			if (!(value instanceof ObjectReference) || value == null) {
				VariableScopeParser parser = new VariableScopeParser();
				LocalVariableScope scope = parser.parseMethodScope(methodDeclaringCompilationUnit, methodLocationLine,
						localVar.getName(), appPath);
				String varID;
				if (scope != null) {
					varID = Variable.concanateLocalVarID(methodDeclaringCompilationUnit, localVar.getName(),
							scope.getStartLine(), scope.getEndLine());
					String definingNodeOrder = this.trace.findDefiningNodeOrder(Variable.WRITTEN, lastestNode, varID);
					varID = varID + ":" + definingNodeOrder;
					localVar.setVarID(varID);
				} else {
					// System.err.println("cannot find the method when parsing
					// parameter scope of " + localVar +
					// methodDeclaringCompilationUnit + "(line " +
					// methodLocationLine +") ");
					System.currentTimeMillis();
				}
			} else {
				ObjectReference ref = (ObjectReference) value;
				String varID = String.valueOf(ref.uniqueID());
				String definingNodeOrder = this.trace.findDefiningNodeOrder(Variable.WRITTEN, lastestNode, varID);
				varID = varID + ":" + definingNodeOrder;
				localVar.setVarID(varID);
			}

			StepVariableRelationEntry entry = this.trace.getStepVariableTable().get(localVar.getVarID());
			if (entry == null && localVar.getVarID() != null) {
				entry = new StepVariableRelationEntry(localVar.getVarID());
				this.trace.getStepVariableTable().put(localVar.getVarID(), entry);
			}

			if (entry != null) {
				entry.addAliasVariable(localVar);
				entry.addProducer(lastestNode);

				VarValue varValue = null;
				if (PrimitiveUtils.isPrimitiveType(param.getType())) {
					if (value != null) {
						varValue = new PrimitiveValue(value.toString(), false, localVar);
					}

				} else {
					varValue = new ReferenceValue(true, false, localVar);
				}

				if (varValue != null && varValue.getVarID() != null) {
					lastestNode.addWrittenVariable(varValue);
				}
			}
		}
	}

	private boolean checkContext(BreakPoint lastSteppingPoint, Location loc) {
		String methodSign1 = lastSteppingPoint.getMethodSign();
		if (methodSign1 == null) {
			return true;
		}
		methodSign1 = methodSign1.substring(methodSign1.lastIndexOf(".") + 1, methodSign1.length());

		String methodSign2 = loc.method().signature();
		methodSign2 = loc.method().name() + methodSign2;

		String class1 = loc.declaringType().signature();
		class1 = SignatureUtils.signatureToName(class1);
		String class2 = lastSteppingPoint.getClassCanonicalName();

		if (methodSign1.equals(methodSign2) && class1.equals(class2)) {
			return false;
		} else {
			return true;
		}
	}

	// private MethodEntryRequest methodEntryRequest;
	// private MethodExitRequest methodExitRequset;

//	/** add watch requests **/
//	private final void addClassWatch(EventRequestManager erm) {
//		/* class watch request for breakpoint */
//		for (String className : brkpsMap.keySet()) {
//			addClassWatch(erm, className);
//		}
//		/* class watch request for junitRunner start point */
//		addClassWatch(erm, ENTER_TC_BKP.getClassCanonicalName());
//	}
//
//	// private ClassPrepareRequest classPrepareRequest;
//	private final void addClassWatch(EventRequestManager erm, String className) {
//		setClassPrepareRequest(erm.createClassPrepareRequest());
//		getClassPrepareRequest().addClassFilter(className);
//		getClassPrepareRequest().setEnabled(true);
//	}
	
	private void parseBreakpoints(VirtualMachine vm, ClassPrepareEvent classPrepEvent,
			Map<String, BreakPoint> locBrpMap) {
		ReferenceType refType = classPrepEvent.referenceType();
		List<BreakPoint> brkpList = CollectionUtils.initIfEmpty(brkpsMap.get(refType.name()));
		for (BreakPoint brkp : brkpList) {
			Location location = checkBreakpoint(vm, refType, brkp.getLineNumber());
			if (location != null) {
				locBrpMap.put(location.toString(), brkp);
			} else {
				System.err.println("Cannot add break point " + brkp);
			}
		}
	}

	private final Location checkBreakpoint(VirtualMachine vm, ReferenceType refType, int lineNumber) {
		List<Location> locations;
		try {
			locations = refType.locationsOfLine(lineNumber);
		} catch (AbsentInformationException e) {
			e.printStackTrace();
			return null;
		}
		if (!locations.isEmpty()) {
			Location location = locations.get(0);
			return location;
		}
		return null;
	}

	private VarValue constructReferenceVarValue(ObjectReference objRef, Variable var, ThreadReference thread, BreakPoint point) {
		VarValue varValue = new ReferenceValue(false, true, var);;
		
		ClassType type = (ClassType)objRef.type();
		boolean needParseFields = HeuristicIgnoringFieldRule.isNeedParsingFields(type);
		if(needParseFields){
			Map<Field, Value> map = objRef.getValues(type.allFields());
			List<Field> fieldList = new ArrayList<>(map.keySet());
			Collections.sort(fieldList, new Comparator<Field>() {
				@Override
				public int compare(Field o1, Field o2) {
					return o1.name().compareTo(o2.name());
				}
			});
			
			VariableValueExtractor extractor = new VariableValueExtractor(point, thread, null, this);
			for(Field field: fieldList){
				if(type.isEnum()){
					String childTypeName = field.typeName();
					if(childTypeName.equals(type.name())){
						continue;
					}
				}
				
				boolean isIgnore = HeuristicIgnoringFieldRule.isForIgnore(type, field);
				if(!isIgnore){
					FieldVar variable = new FieldVar(false, field.name(), field.typeName());
					extractor.appendVarVal(varValue, variable, map.get(field), Settings.getVariableLayer(), thread, false);
				}
			}
		}
		
		return varValue;
	}
	
	private VarValue constructArrayVarValue(ArrayReference arrayValue, Variable var, ThreadReference thread,
			BreakPoint point) {
		ArrayValue arrayVal = new ArrayValue(false, true, var);
		String componentType = ((ArrayType)arrayValue.type()).componentTypeName();
		arrayVal.setComponentType(componentType);
		arrayVal.setReferenceID(arrayValue.uniqueID());
		
		VariableValueExtractor extractor = new VariableValueExtractor(point, thread, null, this);
		//add value of elements
		List<Value> list = new ArrayList<>();
		if(arrayValue.length() > 0){
			list = arrayValue.getValues(0, arrayValue.length()); 
		}
		for(int i = 0; i < arrayValue.length(); i++){
			String varName = String.valueOf(i);
			Value elementValue = list.get(i);
			
			ArrayElementVar varElement = new ArrayElementVar(varName, componentType);
			extractor.appendVarVal(arrayVal, varElement, elementValue, Settings.getVariableLayer(), thread, false);
		}
		
		return arrayVal;
	}

	private VarValue generateVarValue(StackFrame frame, Variable var0, TraceNode node, String accessType, BreakPoint point) {
		VarValue varValue = null;
		/**
		 * Note that the read/written variables in breakpoint should be
		 * different when set them into different trace node.
		 */
		Variable var = var0.clone();
		String varName = var.getName();

		try {
			ExpressionValue expValue = retriveExpression(frame, varName, node.getBreakPoint());
			if (expValue == null) {
				return null;
			}

			Value value = expValue.value;
			if (value instanceof ObjectReference) {
				ObjectReference objRef = (ObjectReference) value;
				String varID = String.valueOf(objRef.uniqueID());

				String definingNodeOrder = this.trace.findDefiningNodeOrder(accessType, node, varID);
				varID = varID + ":" + definingNodeOrder;
				var.setVarID(varID);

				if (value.type().toString().equals("java.lang.String")) {
					String strValue = value.toString();
					strValue = strValue.substring(1, strValue.length() - 1);
					varValue = new StringValue(strValue, false, var);
				} else {
					if (objRef instanceof ArrayReference) {
						ArrayReference arrayValue = (ArrayReference) objRef;
						varValue = constructArrayVarValue(arrayValue, var, frame.thread(), point);
					} else {
						varValue = constructReferenceVarValue(objRef, var, frame.thread(), point);
					}
					
					StringBuffer buffer = new StringBuffer();
					buffer.append("[");
					for(VarValue child: varValue.getChildren()){
						buffer.append(child.getVarName() + "=" + child.getStringValue());
						buffer.append(",");
					}
					buffer.append("]");
					varValue.setStringValue(buffer.toString());
				}
			} 
			/**
			 * its a primitive type
			 */
			else {
				/**
				 * see whether its a local variable
				 */
				if (var instanceof LocalVar) {
					LocalVariableScope scope = this.trace.getLocalVariableScopes().findScope(var.getName(),
							node.getBreakPoint().getLineNumber(),
							node.getBreakPoint().getDeclaringCompilationUnitName());
					System.currentTimeMillis();
					String varID;
					if (scope != null) {
						varID = Variable.concanateLocalVarID(node.getBreakPoint().getDeclaringCompilationUnitName(),
								var.getName(), scope.getStartLine(), scope.getEndLine());
						String definingNodeOrder = this.trace.findDefiningNodeOrder(accessType, node, varID);
						varID = varID + ":" + definingNodeOrder;
					}
					/**
					 * it means that an implicit "this" variable is visited.
					 */
					else if (var.getName().equals("this")) {
						varID = String.valueOf(frame.thisObject().uniqueID());
					} else {
						return null;
					}
					var.setVarID(varID);
				} 
				/**
				 * It's a field or array element.
				 */
				else {
					Value parentValue = expValue.parentValue;
					ObjectReference objRef = (ObjectReference) parentValue;

					if (objRef == null) {
						objRef = null;
						try {
							objRef = frame.thisObject();
						} catch (Exception e) {
						}
						if (objRef == null) {
							return null;
						}
					}

					if (var instanceof FieldVar) {
						String varID = null;
						if (((FieldVar) var).isStatic()) {
							varID = var.getName();
						} else {
							varID = Variable.concanateFieldVarID(String.valueOf(objRef.uniqueID()),
									var.getSimpleName());
						}
						String definingNodeOrder = this.trace.findDefiningNodeOrder(accessType, node, varID);
						varID = varID + ":" + definingNodeOrder;
						var.setVarID(varID);
					} else if (var instanceof ArrayElementVar) {
						String index = var.getSimpleName();
						ExpressionValue indexValue = retriveExpression(frame, index, node.getBreakPoint());
						String indexValueString = indexValue.value.toString();
						String varID = Variable.concanateArrayElementVarID(String.valueOf(objRef.uniqueID()),
								indexValueString);
						String definingNodeOrder = this.trace.findDefiningNodeOrder(accessType, node, varID);
						varID = varID + ":" + definingNodeOrder;
						var.setVarID(varID);

					}
				}

				String content = (value == null) ? null : value.toString();
				varValue = new PrimitiveValue(content, false, var);
			}

			return varValue;
		} catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	

	private StackFrame findFrame(ThreadReference thread, Location location) {
		StackFrame frame = null;
		try {
			for (StackFrame f : thread.frames()) {
				if (f.location().equals(location)) {
					frame = f;
					break;
				}
			}
		} catch (IncompatibleThreadStateException e) {
			e.printStackTrace();
		}

		return frame;
	}

	private void parseReadWrittenVariableInThisStep(ThreadReference thread, Location location, TraceNode node,
			Map<String, StepVariableRelationEntry> stepVariableTable, String action) {

		// try {
		// thread.frames();
		// } catch (IncompatibleThreadStateException e) {
		// e.printStackTrace();
		// }

		if (action.equals(Variable.READ)) {
			processReadVariable(node, stepVariableTable, thread, location, node.getBreakPoint());
		} else if (action.equals(Variable.WRITTEN)) {
			processWrittenVariable(node, stepVariableTable, thread, location, node.getBreakPoint());
		}
	}

	private void processReadVariable(TraceNode node, Map<String, StepVariableRelationEntry> stepVariableTable,
			ThreadReference thread, Location location, BreakPoint point) {

		StackFrame frame = findFrame(thread, location);
		if (frame == null) {
			System.err.println("get a null frame from thread!");
			return;
		}

		synchronized (frame) {
			List<Variable> readVariables = node.getBreakPoint().getReadVariables();
			for (Variable readVar : readVariables) {
				VarValue varValue = generateVarValue(frame, readVar, node, Variable.READ, point);
				if (varValue != null) {
					node.addReadVariable(varValue);
					String varID = varValue.getVarID();

					StepVariableRelationEntry entry = stepVariableTable.get(varID);
					if (entry == null) {
						entry = new StepVariableRelationEntry(varID);
						stepVariableTable.put(varID, entry);
					}
					entry.addAliasVariable(readVar);
					entry.addConsumer(node);
				}
			}
		}
	}

	private void processWrittenVariable(TraceNode node, Map<String, StepVariableRelationEntry> stepVariableTable,
			ThreadReference thread, Location location, BreakPoint point) {
		StackFrame frame = findFrame(thread, location);
		if (frame == null) {
			System.err.println("get a null frame from thread!");
			return;
		}
		synchronized (frame) {
			List<Variable> writtenVariables = node.getBreakPoint().getWrittenVariables();
			for (Variable writtenVar : writtenVariables) {
				VarValue varValue = generateVarValue(frame, writtenVar, node, Variable.WRITTEN, point);

				if (varValue != null) {
					node.addWrittenVariable(varValue);
					String varID = varValue.getVarID();

					StepVariableRelationEntry entry = stepVariableTable.get(varID);
					if (entry == null) {
						entry = new StepVariableRelationEntry(varID);
						stepVariableTable.put(varID, entry);
					}
					entry.addAliasVariable(writtenVar);
					entry.addProducer(node);
				}
			}
		}
	}

	private ExpressionValue retriveExpression(final StackFrame frame0, String expression, BreakPoint point) {
		ThreadReference thread = frame0.thread();
		final StackFrame frame = findFrame(thread, frame0.location());

		ExpressionParser.GetFrame frameGetter = new ExpressionParser.GetFrame() {
			@Override
			public StackFrame get() throws IncompatibleThreadStateException {
				return frame;

			}
		};

		ExpressionValue eValue = null;

		// boolean classPrepare = getClassPrepareRequest().isEnabled();
		// boolean step = getStepRequest().isEnabled();
		// boolean methodEntry = getMethodEntryRequest().isEnabled();
		// boolean methodExit = getMethodExitRequset().isEnabled();
		// boolean exception = getExceptionRequest().isEnabled();
		//
		// getClassPrepareRequest().disable();
		// getStepRequest().disable();
		// getMethodEntryRequest().disable();
		// getMethodExitRequset().disable();
		// getExceptionRequest().disable();

		try {
			ExpressionParser.clear();

			CompilationUnit cu;
			if(point==null){
				cu = null;
			}
			else{
				cu = JavaUtil.findCompilationUnitInProject(point.getDeclaringCompilationUnitName(), appPath);				
			}
			
			int lineNumber = -1;
			if(point != null){
				lineNumber = point.getLineNumber();
			}
			
			ExpressionParser.setParameters(cu, lineNumber);

			Value val = null;
			if (expression.contains("(")) {
				if (expression.contains("[")) {
					val = null;
				} else {
					val = retrieveComplicatedExpressionValue(expression, frame.virtualMachine(), frameGetter);
				}
			} else {
				val = ExpressionParser.evaluate(expression, frame.virtualMachine(), frameGetter);
			}

			eValue = new ExpressionValue(val, ExpressionParser.parentValue, null);

		} catch (ParseException e) {
			// e.printStackTrace();
		} catch (InvocationException e) {
			e.printStackTrace();
		} catch (InvalidTypeException e) {
			e.printStackTrace();
		} catch (ClassNotLoadedException e) {
			e.printStackTrace();
		} catch (IncompatibleThreadStateException e) {
			e.printStackTrace();
		} catch (Exception e) {
			// e.printStackTrace();
		} finally {
			// getClassPrepareRequest().setEnabled(classPrepare);
			// getStepRequest().setEnabled(step);
			// getMethodEntryRequest().setEnabled(methodEntry);
			// getMethodExitRequset().setEnabled(methodExit);
			// getExceptionRequest().setEnabled(exception);
		}

		return eValue;
	}

	private void collectValueOfPreviousStep(BreakPoint lastSteppingInPoint, ThreadReference thread, Location loc)
			throws SavException {

		BreakPoint current = new BreakPoint(lastSteppingInPoint.getClassCanonicalName(),
				lastSteppingInPoint.getDeclaringCompilationUnitName(), lastSteppingInPoint.getLineNumber());
		current.setReadVariables(lastSteppingInPoint.getReadVariables());
		current.setWrittenVariables(lastSteppingInPoint.getWrittenVariables());

		BreakPointValue bkpVal = extractValuesAtLocation(current, thread, loc);

		int len = trace.getExectionList().size();
		TraceNode node = trace.getExectionList().get(len - 1);
		node.setAfterStepInState(bkpVal);

	}

	private TraceNode recordTrace(BreakPoint bkp, BreakPointValue bkpVal) {
		int order = trace.size() + 1;
		TraceNode node = new TraceNode(bkp, bkpVal, order);

		TraceNode stepInPrevious = null;
		if (order >= 2) {
			stepInPrevious = trace.getExectionList().get(order - 2);
		}

		node.setStepInPrevious(stepInPrevious);
		if (stepInPrevious != null) {
			stepInPrevious.setStepInNext(node);
		}

		trace.addTraceNode(node);

		return node;
	}

	private BreakPointValue extractValuesAtLocation(BreakPoint bkp, ThreadReference thread, Location loc)
			throws SavException {
		if (Settings.isRecordSnapshot) {
			try {
				VariableValueExtractor extractor = new VariableValueExtractor(bkp, thread, loc, this);
				BreakPointValue bpValue = extractor.extractValue();
				return bpValue;

			} catch (IncompatibleThreadStateException e) {
				e.printStackTrace();
			} catch (AbsentInformationException e) {
				e.printStackTrace();
			}
			return null;

		} else {
			return new BreakPointValue("");
		}
	}

	public Trace getTrace() {
		return trace;
	}

	public AppJavaClassPath getConfig() {
		return appPath;
	}

	public void setConfig(AppJavaClassPath config) {
		this.appPath = config;
	}

	public StepRequest getStepRequest(ThreadReference thread) {
		for (StepRequest stepRequest : this.stepRequestList) {
			if (stepRequest.thread().equals(thread)) {
				return stepRequest;
			}
		}

		return null;
	}

	public MethodEntryRequest getMethodEntryRequest() {
		return methodEntryRequest;
	}

	public void setMethodEntryRequest(MethodEntryRequest methodEntryRequest) {
		this.methodEntryRequest = methodEntryRequest;
	}

	public MethodExitRequest getMethodExitRequset() {
		return methodExitRequest;
	}

	public void setMethodExitRequset(MethodExitRequest methodExitRequset) {
		this.methodExitRequest = methodExitRequset;
	}

	public ClassPrepareRequest getClassPrepareRequest() {
		return classPrepareRequest;
	}

	public void setClassPrepareRequest(ClassPrepareRequest classPrepareRequest) {
		this.classPrepareRequest = classPrepareRequest;
	}

	public ExceptionRequest getExceptionRequest() {
		return exceptionRequest;
	}

	public void setExceptionRequest(ExceptionRequest exceptionRequest) {
		this.exceptionRequest = exceptionRequest;
	}

	class ExpressionValue {
		Value value;
		/**
		 * used to decide the memory address, this value must be an
		 * ObjectReference.
		 */
		Value parentValue;

		public ExpressionValue(Value value, Value parentValue, Value messageValue) {
			this.value = value;
			this.parentValue = parentValue;

		}

	}
}
