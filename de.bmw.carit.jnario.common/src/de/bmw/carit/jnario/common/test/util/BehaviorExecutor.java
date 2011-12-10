package de.bmw.carit.jnario.common.test.util;

import static com.google.common.collect.Iterables.filter;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.osgi.framework.adaptor.BundleData;
import org.eclipse.osgi.framework.internal.core.AbstractBundle;
import org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader;
import org.eclipse.xtext.builder.internal.Activator;
import org.eclipse.xtext.diagnostics.Severity;
import org.eclipse.xtext.generator.IGenerator;
import org.eclipse.xtext.generator.JavaIoFileSystemAccess;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.CheckMode;
import org.eclipse.xtext.validation.IResourceValidator;
import org.eclipse.xtext.validation.Issue;
import org.junit.experimental.results.PrintableResult;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.JUnitCore;
import org.junit.runner.notification.Failure;
import org.osgi.framework.BundleException;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.inject.Inject;

public abstract class BehaviorExecutor {
	
	private static final String BUNDLE_REFERENCE = "reference:file:";
	private static final String PLUGIN_CLASSES_FOLDER = "plugins";
	private static final String SYSTEM_BUNDLE = "System Bundle";

	private final IGenerator generator;
	private final JavaIoFileSystemAccess fsa;
	private final TemporaryFolder tempFolder;
	private JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
	private IResourceValidator validator;

	@Inject
	public BehaviorExecutor(IGenerator generator, JavaIoFileSystemAccess fsa,
			TemporaryFolder tempFolder, IResourceValidator validator) {
		this.generator = generator;
		this.fsa = fsa;
		this.tempFolder = tempFolder;
		this.validator = validator;
	}

	public PrintableResult run(EObject object) {
		try {
			configureOutlet();
			validate(object);
			generateJava(object);
			return runExamples(object);
		} catch (Exception e) {
			e.printStackTrace();
			fail(e.getMessage());
			return null; // not reachable
		} finally {
			tempFolder.delete();
		}
	}

	private void validate(EObject object) {
		Iterable<Issue> issues = validator.validate(object.eResource(), CheckMode.NORMAL_AND_FAST, CancelIndicator.NullImpl);
		Iterable<Issue> onlyErrors = filterErrors(issues);
		//assertFalse("Validation errors\n" + Joiner.on("\n  ").join(issues), onlyErrors.iterator().hasNext());
	}

	public Iterable<Issue> filterErrors(Iterable<Issue> issues) {
		Iterable<Issue> onlyErrors = filter(issues, new Predicate<Issue>() {

			public boolean apply(Issue input) {
				return input.getSeverity() == Severity.ERROR;
			}
		});
		return onlyErrors;
	}

	private void configureOutlet() throws IOException {
		tempFolder.create();
		fsa.setOutputPath(tempFolder.getRoot().getAbsolutePath());
	}

	protected void generateJava(EObject object) {
		generator.doGenerate(object.eResource(), fsa);
	}

	protected abstract PrintableResult runExamples(EObject object)
			throws MalformedURLException, ClassNotFoundException;

	protected void compileJavaFile(String packageName, String className) {
		String javaFile = getGeneratedJavaClassName(packageName, className);
		if(isLoadedAsPlugin()){
			ArrayList<String> classPath = getClassPath();
			String pathes = Joiner.on(";").join(classPath.toArray());
			compiler.run(System.in, System.out, System.err, new String[]{"-classpath", pathes, javaFile});
		}
		else{
			compiler.run(System.in, System.out, System.err, javaFile);
		}
	}

	protected List<Failure> execute(Class<?> cls) {
		return JUnitCore.runClasses(cls).getFailures();
	}

	protected Class<?> loadGeneratedClass(String packageName, String specClassName)
			throws MalformedURLException, ClassNotFoundException {
		URLClassLoader classLoader = URLClassLoader
				.newInstance(new URL[] { tempFolder.getRoot().toURI().toURL() }, getClass().getClassLoader());
		String className = packageName + "." + specClassName;
		return Class.forName(className, true, classLoader);
	}

	private String getGeneratedJavaClassName(String packageName, String specClassName) {
		StringBuilder sb = new StringBuilder();
		sb.append(tempFolder.getRoot().getAbsolutePath());
		sb.append(File.separator);
		sb.append(packageName.replaceAll("\\.", File.separator + File.separator));
		sb.append(File.separator);
		sb.append(specClassName);
		sb.append(".java");
		return sb.toString();
	}

	protected List<Failure> runTestsInClass(String className, String packageName, List<Failure> failures)
			throws MalformedURLException, ClassNotFoundException {
		compileJavaFile(packageName, className);
		Class<?> testClass = loadGeneratedClass(packageName, className);
		List<Failure> newFailures = execute(testClass);
		failures.addAll(newFailures);
		return failures;
	}
	
	private boolean isLoadedAsPlugin(){
		ClassLoader pluginClassLoader = getClass().getClassLoader();
		if(pluginClassLoader instanceof DefaultClassLoader){
			return true;
		}
		return false;
	}
	
	private ArrayList<String> getClassPath(){
		
		String installLocation = Platform.getInstallLocation().getURL().getPath();
		
		AbstractBundle[] bundles = (AbstractBundle[])Activator.getDefault().getBundle().getBundleContext().getBundles();
		ArrayList<String> classpath = new ArrayList<String>();
		
		for(AbstractBundle bundle: bundles){
			BundleData bundleData = bundle.getBundleData();
			String pathToBundle = getPathOfBundle(bundleData);
			try {
				for(String subFolders: bundleData.getClassPath()){
					String fullLocation = pathToBundle + File.separator + subFolders;
					String finalClassPath = getAbsoluteClassPath(fullLocation, installLocation);
					classpath.add(finalClassPath.replace("/", File.separator));
				}
			} catch (BundleException e) {
				e.printStackTrace();
			}
		}
		return classpath;
	}
	
	private String getPathOfBundle(BundleData bundleData){
		String pathOfBundle = bundleData.getLocation();
		int indexOf = pathOfBundle.indexOf(BUNDLE_REFERENCE);
		if(indexOf >= 0){
			return pathOfBundle.substring(indexOf + BUNDLE_REFERENCE.length());
		}
		return pathOfBundle;
	}
	
	private String getAbsoluteClassPath(String fullLocation, String installLocation){
		if(fullLocation.contains(SYSTEM_BUNDLE)){
			return "";
		}
		if(fullLocation.contains("..") || fullLocation.startsWith(PLUGIN_CLASSES_FOLDER)){
			return installLocation + File.separator + fullLocation;
		}
		return fullLocation;
	}

}