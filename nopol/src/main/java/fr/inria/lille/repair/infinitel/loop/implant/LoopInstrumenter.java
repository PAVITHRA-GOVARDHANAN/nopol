package fr.inria.lille.repair.infinitel.loop.implant;

import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newBlock;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newBreak;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newExpressionFromSnippet;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newIf;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newLiteral;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newLocalVariableDeclaration;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newStatementFromSnippet;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newThrow;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.newTryCatch;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.setLoopBody;
import static fr.inria.lille.commons.spoon.util.SpoonModelLibrary.setLoopingCondition;
import static fr.inria.lille.commons.spoon.util.SpoonStatementLibrary.insertAfterUnderSameParent;
import static fr.inria.lille.commons.spoon.util.SpoonStatementLibrary.insertBeforeUnderSameParent;
import static java.lang.String.format;

import java.util.Collection;
import java.util.Map;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtTry;
import spoon.reflect.code.CtWhile;
import spoon.reflect.factory.Factory;
import xxl.java.container.classic.MetaMap;
import xxl.java.support.Singleton;
import fr.inria.lille.commons.spoon.collectable.CollectableValueFinder;
import fr.inria.lille.commons.trace.RuntimeValues;
import fr.inria.lille.commons.trace.RuntimeValuesInstrumenter;
import fr.inria.lille.repair.infinitel.loop.While;

public class LoopInstrumenter {

	public static void instrument(LoopMonitor monitor, RuntimeValues<?> runtimeValues) {
		While loop = monitor.loop();
		CtWhile astLoop = loop.astLoop();
		Factory factory = astLoop.getFactory();
		Collection<String> collectables = collectableFinder().findFromWhile(astLoop);
		CtStatement catchCallback = appendMonitorCallbacks(factory, monitor, loop, astLoop);
		CtIf newIf = loopBodyWrapper(factory, monitor, loop, astLoop, catchCallback);
		declareOriginalConditionEvaluation(factory, monitor, loop, newIf);
		declareConditionEvaluation(factory, monitor, loop, newIf);
		declareEntrancesCounter(factory, monitor, astLoop);
		traceReachableValues(monitor, loop, newIf, collectables, runtimeValues);
	}

	private static CtStatement appendMonitorCallbacks(Factory factory, LoopMonitor monitor, While loop, CtWhile astLoop) {
		String counterName = counterName(monitor);
		if (! loop.isUnbreakable()) {
			insertAfterUnderSameParent(newStatementFromSnippet(factory, monitor.invocationOnMonitoringEnd(counterName)), astLoop);
		}
		appendMonitoredReturnExit(factory, monitor, loop.returnStatements());
		appendMonitoredBreakExit(factory, monitor, loop.breakStatements());
		return newStatementFromSnippet(factory, monitor.invocationOnLoopError(counterName));
	}

	private static void appendMonitoredReturnExit(Factory factory, LoopMonitor monitor, Collection<CtReturn<?>> returns) {
		String counterName = counterName(monitor);
		for (CtReturn<?> returnStatement : returns) {
			CtStatement invocationOnLoopReturn = newStatementFromSnippet(factory, monitor.invocationOnLoopReturn(counterName));
			insertBeforeUnderSameParent(invocationOnLoopReturn, returnStatement);
		}
	}

	private static void appendMonitoredBreakExit(Factory factory, LoopMonitor monitor, Collection<CtBreak> breaks) {
		String counterName = counterName(monitor);
		for (CtBreak breakStatement : breaks) {
			CtStatement invocationOnLoopBreak = newStatementFromSnippet(factory, monitor.invocationOnLoopBreak(counterName));
			insertBeforeUnderSameParent(invocationOnLoopBreak, breakStatement);
		}
	}
	
	private static CtIf loopBodyWrapper(Factory factory, LoopMonitor monitor, While loop, CtWhile astLoop, CtStatement catchCallback) {
		CtIf newIf = originalLoopReplacement(factory, monitor, loop);
		CtBlock<?> catchBlock = newBlock(factory, catchCallback, newThrow(factory, Throwable.class, catchName(monitor)));
		CtTry tryCatch = newTryCatch(factory, newIf, Throwable.class, catchName(monitor), catchBlock, astLoop);
		setLoopBody(astLoop, tryCatch);
		setLoopingCondition(astLoop, newLiteral(factory, true));
		return newIf;
	}

	private static CtIf originalLoopReplacement(Factory factory, LoopMonitor monitor, While loop) {
		CtExpression<Boolean> monitoredCondition = newExpressionFromSnippet(factory, conditionName(monitor), Boolean.class);
		CtIf newIf = newIf(factory, monitoredCondition, loop.loopBody(), newBreak(factory));
		CtStatement increment = newStatementFromSnippet(factory, format("%s += 1", counterName(monitor)));
		insertBeforeUnderSameParent(increment, newIf.getThenStatement());
		if (loop.isUnbreakable()) {
			newIf.setElseStatement(null);
		}
		return newIf;
	}
	
	private static void declareOriginalConditionEvaluation(Factory factory, LoopMonitor monitor, While loop, CtIf newIf) {
		CtLocalVariable<Boolean> localVariable = newLocalVariableDeclaration(factory, boolean.class, originalConditionName(monitor), loop.loopingCondition());
		insertBeforeUnderSameParent(localVariable, newIf);
	}
	
	private static void declareConditionEvaluation(Factory factory, LoopMonitor monitor, While loop, CtIf newIf) {
		String conditionInvocation = monitor.invocationOnLoopConditionEvaluation(originalConditionName(monitor), counterName(monitor));
		CtLocalVariable<Boolean> localVariable = newLocalVariableDeclaration(factory, boolean.class, conditionName(monitor), conditionInvocation);
		insertBeforeUnderSameParent(localVariable, newIf);
	}
	
	private static void declareEntrancesCounter(Factory factory, LoopMonitor monitor, CtWhile astLoop) {
		CtLocalVariable<Integer> counterCreation = newLocalVariableDeclaration(factory, int.class, counterName(monitor), 0, astLoop.getParent());
		insertBeforeUnderSameParent(counterCreation, astLoop);
	}

	private static void traceReachableValues(LoopMonitor monitor, While loop, CtIf newIf, Collection<String> inputs, RuntimeValues<?> runtimeValues) {
		Map<String, String> inputMap = MetaMap.autoMap(inputs);
		inputMap.put(loop.loopingCondition(), originalConditionName(monitor));
		RuntimeValuesInstrumenter.runtimeCollectionBefore(newIf, inputMap, conditionName(monitor), runtimeValues);
	}
	
	private static CollectableValueFinder collectableFinder() {
		return Singleton.of(CollectableValueFinder.class);
	}
	
	private static String counterName(LoopMonitor monitor) {
		return "loopEntrancesCounter_" + monitor.instanceID();
	}
	
	private static String catchName(LoopMonitor monitor) {
		return "loopMonitorCatch_" + monitor.instanceID();
	}
	
	private static String conditionName(LoopMonitor monitor) {
		return "loopConditionEvaluation_" + monitor.instanceID();
	}
	
	private static String originalConditionName(LoopMonitor monitor) {
		return "loopOriginalCondition_" + monitor.instanceID();
	}
}
