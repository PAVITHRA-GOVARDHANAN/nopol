package fr.inria.lille.commons.compiler;

import static fr.inria.lille.commons.utils.LoggerLibrary.logDebug;
import static fr.inria.lille.commons.utils.LoggerLibrary.newLoggerFor;
import static java.lang.String.format;
import static java.util.Arrays.asList;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.slf4j.Logger;

import fr.inria.lille.commons.collections.ListLibrary;
import fr.inria.lille.commons.collections.MapLibrary;
import fr.inria.lille.commons.string.StringLibrary;

public class DynamicClassCompiler {
	
	public DynamicClassCompiler() {
		options = asList("-nowarn");
		compiler = ToolProvider.getSystemJavaCompiler();
		diagnostics = new DiagnosticCollector<JavaFileObject>();
		StandardJavaFileManager standardFileManager = compiler().getStandardFileManager(diagnostics(), null, null);
		fileManager = new VirtualFileObjectManager(standardFileManager);
	}

	public synchronized byte[] javaBytecodeFor(String qualifiedName, String sourceContent) {
		return javaBytecodeFor(qualifiedName, sourceContent, new HashMap<String, byte[]>());
	}
	
	public synchronized byte[] javaBytecodeFor(String qualifiedName, String sourceContent, Map<String, byte[]> compiledDependencies) {
		Map<String, String> adHocMap = MapLibrary.newHashMap(qualifiedName, sourceContent);
		return javaBytecodeFor(adHocMap, compiledDependencies).get(qualifiedName);
	}
	
	public synchronized Map<String, byte[]> javaBytecodeFor(Map<String, String> qualifiedNameAndContent) {
		return javaBytecodeFor(qualifiedNameAndContent, new HashMap<String, byte[]>());
	}
	
	public synchronized Map<String, byte[]> javaBytecodeFor(Map<String, String> qualifiedNameAndContent, Map<String, byte[]> compiledDependencies) {
		logDebug(logger, format("[Compiling %d source files]", qualifiedNameAndContent.size()));
		Collection<JavaFileObject> units = addCompilationUnits(qualifiedNameAndContent);
		fileManager().addCompiledClasses(compiledDependencies);
		CompilationTask task = compiler().getTask(null, fileManager(), diagnostics(), options(), null, units);
		runCompilationTask(task);
		Map<String, byte[]> bytecodes = collectBytecodes(qualifiedNameAndContent);
		logDebug(logger, format("[Compilation finished successfully (%d classes)]", bytecodes.size()));
		return bytecodes;
	}
	
	protected Collection<JavaFileObject> addCompilationUnits(Map<String, String> qualifiedNameAndContent) {
		Collection<JavaFileObject> units = ListLibrary.newArrayList();
		for (String qualifiedName : qualifiedNameAndContent.keySet()) {
			String sourceContent = qualifiedNameAndContent.get(qualifiedName);
			JavaFileObject sourceFile = addCompilationUnit(qualifiedName, sourceContent);
			units.add(sourceFile);
		}
		return units;
	}

	protected JavaFileObject addCompilationUnit(String qualifiedName, String sourceContent) {
		String simpleClassName = StringLibrary.lastAfterSplit(qualifiedName, "[.]");
		String packageName = StringLibrary.stripEnd(qualifiedName, "." + simpleClassName);
		VirtualSourceFileObject sourceFile = new VirtualSourceFileObject(simpleClassName, sourceContent);
		fileManager().addSourceFile(StandardLocation.SOURCE_PATH, packageName, simpleClassName, sourceFile);
		return sourceFile;
	}
	
	protected boolean runCompilationTask(CompilationTask task) {
		boolean success = task.call();
		if (! success) {
			Collection<String> errors = ListLibrary.newArrayList("[Compilation errors]");
			for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics().getDiagnostics()) {
				errors.add(diagnostic.toString());
			}
			logDebug(logger, errors);
			throw new DynamicCompilationException("Aborting: dynamic compilation failed");
		}
		return success;
	}
	
	private Map<String, byte[]> collectBytecodes(Map<String, String> qualifiedNameAndContent) {
		Map<String, byte[]> bytecodes = MapLibrary.newHashMap();
		Map<String, VirtualClassFileObject> classFiles = fileManager().classFiles();
		for (String qualifiedName : classFiles.keySet()) {
			String topClassName = topClassName(qualifiedName);
			if (qualifiedNameAndContent.containsKey(topClassName)) {
				bytecodes.put(qualifiedName, classFiles.get(qualifiedName).byteCodes());
			}
		}
		return bytecodes;
	}
	
	private String topClassName(String qualifiedName) {
		return qualifiedName.split("[$]")[0];
	}
	
	protected VirtualFileObjectManager fileManager() {
		return fileManager;
	}
	
	private List<String> options() {
		return options;
	}
	
	private JavaCompiler compiler() {
		return compiler;
	}
	
	private DiagnosticCollector<JavaFileObject> diagnostics() {
		return diagnostics;
	}
	
	private List<String> options;
	private JavaCompiler compiler;
	private VirtualFileObjectManager fileManager;
	private DiagnosticCollector<JavaFileObject> diagnostics;
	
	private static Logger logger = newLoggerFor(DynamicClassCompiler.class);
}