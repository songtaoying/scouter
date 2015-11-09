/*
 *  Copyright 2015 the original author or authors. 
 *  @https://github.com/scouter-project/scouter
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); 
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *
 */
package scouter.agent.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;

import scouter.agent.Configure;
import scouter.agent.Logger;
import scouter.agent.trace.HookPoint;
import scouter.javassist.CannotCompileException;
import scouter.javassist.ClassPool;
import scouter.javassist.CtClass;
import scouter.javassist.CtMethod;
import scouter.javassist.CtNewMethod;
import scouter.lang.pack.XLogPack;
import scouter.util.FileUtil;
import scouter.util.Hexa32;
import scouter.util.StringUtil;
import scouter.util.ThreadUtil;

public class PlugInLoader extends Thread {
	private static PlugInLoader instance;

	public synchronized static PlugInLoader getInstance() {
		if (instance == null) {
			instance = new PlugInLoader();
			instance.setDaemon(true);
			instance.setName("PlugInLoader");
			instance.start();
		}
		return instance;
	}

	public void run() {
		while (true) {
			ThreadUtil.sleep(5000);
			try {
				File root = Configure.getInstance().plugin_dir;
				cleckPluginModified(root);
			} catch (Throwable t) {
				Logger.println("A160", t.toString());
			}
		}
	}

	private void cleckPluginModified(File root) {
		File script = new File(root, "service.plug");
		if (script.canRead() == false) {
			ServiceTracePlugIn.plugIn = null;
		} else {
			if (ServiceTracePlugIn.plugIn == null || ServiceTracePlugIn.plugIn.lastModified != script.lastModified()) {
				ServiceTracePlugIn.plugIn = createIServiceTrace(script);
			}
		}
		script = new File(root, "httpservice.plug");
		if (script.canRead() == false) {
			HttpServiceTracePlugIn.plugIn = null;
		} else {
			if (HttpServiceTracePlugIn.plugIn == null
					|| HttpServiceTracePlugIn.plugIn.lastModified != script.lastModified()) {
				HttpServiceTracePlugIn.plugIn = createIHttpService(script);
			}
		}
		script = new File(root, "capture.plug");
		if (script.canRead() == false) {
			CapturePlugIn.plugIn = null;
		} else {
			if (CapturePlugIn.plugIn == null
					|| CapturePlugIn.plugIn.lastModified != script.lastModified()) {
				CapturePlugIn.plugIn = createICaptureTrace(script);
			}
		}
	}

	private long IHttpServiceCompile;

	private IHttpService createIHttpService(File script) {
		if (IHttpServiceCompile == script.lastModified())
			return null;
		IHttpServiceCompile = script.lastModified();
		try {
			HashMap<String, StringBuffer> bodyTable = loadFileText(script);
			String superName = IHttpService.class.getName();
			String className = "scouter.agent.plugin.impl.HttpServiceImpl";
			String START = "start";
			String START_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(RequestWrapper.class)
					+ nativeName(ResponseWrapper.class) + ")V";
			String START_P1 = ContextWrapper.class.getName();
			String START_P2 = RequestWrapper.class.getName();
			String START_P3 = ResponseWrapper.class.getName();
			StringBuffer START_BODY = bodyTable.get(START);
			if (START_BODY == null)
				throw new CannotCompileException("no method body: " + START);
			String END = "end";
			String END_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(XLogPack.class) + ")V";
			String END_P1 = ContextWrapper.class.getName();
			String END_P2 = XLogPack.class.getName();
			StringBuffer END_BODY = bodyTable.get(END);
			if (END_BODY == null)
				throw new CannotCompileException("no method body: " + END);
			String REJECT = "reject";
			String REJECT_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(RequestWrapper.class)
					+ nativeName(ResponseWrapper.class) + ")Z";
			String REJECT_P1 = ContextWrapper.class.getName();
			String REJECT_P2 = RequestWrapper.class.getName();
			String REJECT_P3 = ResponseWrapper.class.getName();
			StringBuffer REJECT_BODY = bodyTable.get(REJECT);
			if (REJECT_BODY == null)
				throw new CannotCompileException("no method body: " + REJECT);
			ClassPool cp = ClassPool.getDefault();
			String jar = FileUtil.getJarFileName(PlugInLoader.class);
			if (jar != null) {
				cp.appendClassPath(jar);
			}
			CtClass cc = cp.get(superName);
			CtClass impl = null;
			try {
				impl = cp.get(className);
				impl.defrost();
				// START METHOD
				CtMethod method = impl.getMethod(START, START_SIG);
				method.setBody("{" + START_P1 + " $ctx=$1;" + START_P2 + " $req=$2;" + START_P3 + " $res=$3;"
						+ START_BODY + "}");
				// END METHOD
				method = impl.getMethod(END, END_SIG);
				method.setBody("{" + END_P1 + " $ctx=$1;" + END_P2 + " $pack=$2;" + END_BODY + "}");
				// REJECT METHOD
				method = impl.getMethod(START, REJECT_SIG);
				method.setBody("{" + REJECT_P1 + " $ctx=$1;" + REJECT_P2 + " $req=$2;" + REJECT_P3 + " $res=$3;"
						+ REJECT_BODY + "}");
			} catch (scouter.javassist.NotFoundException e) {
				impl = cp.makeClass(className, cc);
				// START METHOD
				CtMethod method = CtNewMethod.make("public void " + START + "(" + START_P1 + " p1, " + START_P2
						+ " p2, " + START_P3 + " p3){}", impl);
				impl.addMethod(method);
				method.setBody("{" + START_P1 + " $ctx=$1;" + START_P2 + " $req=$2;" + START_P3 + " $res=$3;"
						+ START_BODY + "}");
				// END METHOD
				method = CtNewMethod.make("public void " + END + "(" + END_P1 + " p1, " + END_P2 + " p2){}", impl);
				impl.addMethod(method);
				method.setBody("{" + END_P1 + " $ctx=$1;" + END_P2 + " $pack=$2;" + END_BODY + "}");
				// REJECT METHOD
				method = CtNewMethod.make("public boolean " + REJECT + "(" + REJECT_P1 + " p1, " + REJECT_P2 + " p2, "
						+ REJECT_P3 + " p3){ return false; }", impl);
				impl.addMethod(method);
				method.setBody("{" + REJECT_P1 + " $ctx=$1;" + REJECT_P2 + " $req=$2;" + REJECT_P3 + " $res=$3;"
						+ REJECT_BODY + "}");
			}
			Class c = impl.toClass(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), null);
			IHttpService plugin = (IHttpService) c.newInstance();
			plugin.lastModified = script.lastModified();
			Logger.info("PLUG-IN : " + IHttpService.class.getName() + " loaded #"
					+ Hexa32.toString32(plugin.hashCode()));
			return plugin;
		} catch (scouter.javassist.CannotCompileException ee) {
			Logger.info(ee.getMessage());
		} catch (Throwable e) {
			Logger.println("A161", e);
		}
		return null;
	}

	private HashMap<String, StringBuffer> loadFileText(File script) {
		StringBuffer sb = new StringBuffer();
		HashMap<String, StringBuffer> result = new HashMap<String, StringBuffer>();
		String txt = new String(FileUtil.readAll(script));
		try {
			BufferedReader r = new BufferedReader(new StringReader(txt));
			while (true) {
				String line = StringUtil.trim(r.readLine());
				if (line == null)
					break;
				if (line.startsWith("[") && line.endsWith("]")) {
					sb = new StringBuffer();
					result.put(line.substring(1, line.length() - 1), sb);
				} else {
					sb.append(line).append("\n");
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return result;
	}

	private long IServiceTraceCompile;

	private IServiceTrace createIServiceTrace(File script) {
		if (IServiceTraceCompile == script.lastModified())
			return null;
		IServiceTraceCompile = script.lastModified();
		try {
			HashMap<String, StringBuffer> bodyTable = loadFileText(script);
			String superName = IServiceTrace.class.getName();
			String className = "scouter.agent.plugin.impl.ServiceTraceImpl";
			String START = "start";
			String START_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(HookPoint.class) + ")V";
			String START_P1 = ContextWrapper.class.getName();
			String START_P2 = HookPoint.class.getName();
			StringBuffer START_BODY = bodyTable.get(START);
			if (START_BODY == null)
				throw new CannotCompileException("no method body: " + START);
			String END = "end";
			String END_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(XLogPack.class) + ")V";
			String END_P1 = ContextWrapper.class.getName();
			String END_P2 = XLogPack.class.getName();
			StringBuffer END_BODY = bodyTable.get(END);
			if (END_BODY == null)
				throw new CannotCompileException("no method body: " + END);
			ClassPool cp = ClassPool.getDefault();
			String jar = FileUtil.getJarFileName(PlugInLoader.class);
			if (jar != null) {
				cp.appendClassPath(jar);
			}
			Class c = null;
			CtClass cc = cp.get(superName);
			CtClass impl = null;
			try {
				impl = cp.get(className);
				impl.defrost();
				// START METHOD
				CtMethod method = impl.getMethod(START, START_SIG);
				method.setBody("{" + START_P1 + " $ctx=$1;" + START_P2 + " $hook=$2;" + START_BODY + "}");
				// END METHOD
				method = impl.getMethod(END, END_SIG);
				method.setBody("{" + END_P1 + " $ctx=$1;" + END_P2 + " $pack=$2;" + END_BODY + "}");
			} catch (scouter.javassist.NotFoundException e) {
				impl = cp.makeClass(className, cc);
				// START METHOD
				CtMethod method = CtNewMethod.make("public void " + START + "(" + START_P1 + " p1, " + START_P2
						+ " p2){}", impl);
				impl.addMethod(method);
				method.setBody("{" + START_P1 + " $ctx=$1;" + START_P2 + " $hook=$2;" + START_BODY + "}");
				// END METHOD
				method = CtNewMethod.make("public void " + END + "(" + END_P1 + " p1, " + END_P2 + " p2){}", impl);
				impl.addMethod(method);
				method.setBody("{" + END_P1 + " $ctx=$1;" + END_P2 + " $pack=$2;" + END_BODY + "}");
			}
			c = impl.toClass(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), null);
			IServiceTrace plugin = (IServiceTrace) c.newInstance();
			plugin.lastModified = script.lastModified();
			Logger.info("PLUG-IN : " + IServiceTrace.class.getName() + " loaded #"
					+ Hexa32.toString32(plugin.hashCode()));
			return plugin;
		} catch (scouter.javassist.CannotCompileException ee) {
			Logger.info("PLUG-IN : " + ee.getMessage());
		} catch (Exception e) {
			Logger.println("A162", e);
		}
		return null;
	}

	private long ICaptureCompile;

	private ICapture createICaptureTrace(File script) {
		if (ICaptureCompile == script.lastModified())
			return null;
		ICaptureCompile = script.lastModified();
		try {
			HashMap<String, StringBuffer> bodyTable = loadFileText(script);
			String superName = ICapture.class.getName();
			String className = "scouter.agent.plugin.impl.CaptureImpl";
			String ARG = "capArgs";
			String ARG_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(String.class)
					+ nativeName(String.class) + nativeName(String.class) + "[" + nativeName(Object.class) + ")V";
			String ARG_P1 = ContextWrapper.class.getName();
			String ARG_P2 = String.class.getName();
			String ARG_P3 = String.class.getName();
			String ARG_P4 = String.class.getName();
			String ARG_P5 = "Object[]";
			StringBuffer ARG_BODY = bodyTable.get("args");

			String RTN = "capReturn";
			String RTN_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(String.class)
					+ nativeName(String.class) + nativeName(String.class) + nativeName(Object.class) + ")V";
			String RTN_P1 = ContextWrapper.class.getName();
			String RTN_P2 = String.class.getName();
			String RTN_P3 = String.class.getName();
			String RTN_P4 = String.class.getName();
			String RTN_P5 = "Object";
			StringBuffer RTN_BODY = bodyTable.get("return");

			String THIS = "capThis";
			String THIS_SIG = "(" + nativeName(ContextWrapper.class) + nativeName(String.class)
					+ nativeName(String.class) + nativeName(Object.class) + ")V";
			String THIS_P1 = ContextWrapper.class.getName();
			String THIS_P2 = String.class.getName();
			String THIS_P3 = String.class.getName();
			String THIS_P4 = "Object";
			StringBuffer THIS_BODY = bodyTable.get("this");

			ClassPool cp = ClassPool.getDefault();
			String jar = FileUtil.getJarFileName(PlugInLoader.class);
			if (jar != null) {
				cp.appendClassPath(jar);
			}
			Class c = null;
			CtClass cc = cp.get(superName);
			CtClass impl = null;
			try {
				impl = cp.get(className);
				impl.defrost();
				// ARG METHOD
				CtMethod method = impl.getMethod(ARG, ARG_SIG);
				StringBuffer sb = new StringBuffer();
				sb.append("{");
				sb.append(ARG_P1).append(" $ctx=$1;");
				sb.append(ARG_P2).append(" $class=$2;");
				sb.append(ARG_P3).append(" $method=$3;");
				sb.append(ARG_P4).append(" $desc=$4;");
				sb.append(ARG_P5).append(" $data=$5;");
				sb.append(ARG_BODY);
				sb.append("}");
				method.setBody(sb.toString());

				// RETURN METHOD
				method = impl.getMethod(RTN, RTN_SIG);
				sb = new StringBuffer();
				sb.append("{");
				sb.append(RTN_P1).append(" $ctx=$1;");
				sb.append(RTN_P2).append(" $class=$2;");
				sb.append(RTN_P3).append(" $method=$3;");
				sb.append(RTN_P4).append(" $desc=$4;");
				sb.append(RTN_P5).append(" $data=$5;");
				sb.append(RTN_BODY);
				sb.append("}");
				method.setBody(sb.toString());

				// THIS METHOD
				method = impl.getMethod(THIS, THIS_SIG);
				sb = new StringBuffer();
				sb.append("{");
				sb.append(THIS_P1).append(" $ctx=$1;");
				sb.append(THIS_P2).append(" $class=$2;");
				sb.append(THIS_P3).append(" $desc=$3;");
				sb.append(THIS_P4).append(" $data=$4;");
				sb.append(THIS_BODY);
				sb.append("}");
				method.setBody(sb.toString());

			} catch (scouter.javassist.NotFoundException e) {
				impl = cp.makeClass(className, cc);
				// ARG METHOD
				StringBuffer sb = new StringBuffer();
				sb.append("public void ").append(ARG).append("(");
				sb.append(ARG_P1).append(" p1 ").append(",");
				sb.append(ARG_P2).append(" p2 ").append(",");
				sb.append(ARG_P3).append(" p3 ").append(",");
				sb.append(ARG_P4).append(" p4 ").append(",");
				sb.append(ARG_P5).append(" p5 ");
				sb.append("){}");

				CtMethod method = CtNewMethod.make(sb.toString(), impl);
				impl.addMethod(method);
				sb = new StringBuffer();
				sb.append("{");
				sb.append(ARG_P1).append(" $ctx=$1;");
				sb.append(ARG_P2).append(" $class=$2;");
				sb.append(ARG_P3).append(" $method=$3;");
				sb.append(ARG_P4).append(" $desc=$4;");
				sb.append(ARG_P5).append(" $data=$5;");
				sb.append(ARG_BODY);
				sb.append("}");
				method.setBody(sb.toString());

				// RTN METHOD
				sb = new StringBuffer();
				sb.append("public void ").append(RTN).append("(");
				sb.append(RTN_P1).append(" p1 ").append(",");
				sb.append(RTN_P2).append(" p2 ").append(",");
				sb.append(RTN_P3).append(" p3 ").append(",");
				sb.append(RTN_P4).append(" p4 ").append(",");
				sb.append(RTN_P5).append(" p5 ");
				sb.append("){}");
				method = CtNewMethod.make(sb.toString(), impl);
				impl.addMethod(method);
				sb = new StringBuffer();
				sb.append("{");
				sb.append(RTN_P1).append(" $ctx=$1;");
				sb.append(RTN_P2).append(" $class=$2;");
				sb.append(RTN_P3).append(" $method=$3;");
				sb.append(RTN_P4).append(" $desc=$4;");
				sb.append(RTN_P5).append(" $data=$5;");
				sb.append(RTN_BODY);
				sb.append("}");
				method.setBody(sb.toString());

				// THIS METHOD
				sb = new StringBuffer();
				sb.append("public void ").append(THIS).append("(");
				sb.append(THIS_P1).append(" p1 ").append(",");
				sb.append(THIS_P2).append(" p2 ").append(",");
				sb.append(THIS_P3).append(" p3 ").append(",");
				sb.append(THIS_P4).append(" p4 ");
				sb.append("){}");
				method = CtNewMethod.make(sb.toString(), impl);
				impl.addMethod(method);
				sb = new StringBuffer();
				sb.append("{");
				sb.append(THIS_P1).append(" $ctx=$1;");
				sb.append(THIS_P2).append(" $class=$2;");
				sb.append(THIS_P3).append(" $desc=$3;");
				sb.append(THIS_P4).append(" $data=$4;");
				sb.append(THIS_BODY);
				sb.append("}");
				method.setBody(sb.toString());

			}
			c = impl.toClass(new URLClassLoader(new URL[0], this.getClass().getClassLoader()), null);
			ICapture plugin = (ICapture) c.newInstance();
			plugin.lastModified = script.lastModified();
			Logger.info("PLUG-IN : " + IServiceTrace.class.getName() + " loaded #"
					+ Hexa32.toString32(plugin.hashCode()));
			return plugin;
		} catch (scouter.javassist.CannotCompileException ee) {
			Logger.info("PLUG-IN : " + ee.getMessage());
		} catch (Exception e) {
			Logger.println("A162", e);
		}
		return null;
	}

	private String nativeName(Class class1) {
		return "L" + class1.getName().replace('.', '/') + ";";
	}
}