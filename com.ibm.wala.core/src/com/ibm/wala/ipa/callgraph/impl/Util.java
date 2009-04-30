/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.ipa.callgraph.impl;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.ClassTargetSelector;
import com.ibm.wala.ipa.callgraph.ContextSelector;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.MethodTargetSelector;
import com.ibm.wala.ipa.callgraph.propagation.SSAContextInterpreter;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXCFABuilder;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXContainerCFABuilder;
import com.ibm.wala.ipa.callgraph.propagation.cfa.ZeroXInstanceKeys;
import com.ibm.wala.ipa.callgraph.propagation.rta.BasicRTABuilder;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.summaries.BypassClassTargetSelector;
import com.ibm.wala.ipa.summaries.BypassMethodTargetSelector;
import com.ibm.wala.ipa.summaries.XMLMethodSummaryReader;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeName;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.traverse.SlowDFSDiscoverTimeIterator;
import com.ibm.wala.util.strings.Atom;

/**
 * Call graph utilities
 */
public class Util {
  /**
   * TODO: Make these properties?
   */
  public static final String nativeSpec = "natives.xml";

  /**
   * Set up an AnalysisOptions object with default selectors, corresponding to class hierarchy lookup
   * 
   * @throws IllegalArgumentException if options is null
   */
  public static void addDefaultSelectors(AnalysisOptions options, IClassHierarchy cha) {
    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    options.setSelector(new ClassHierarchyMethodTargetSelector(cha));
    options.setSelector(new ClassHierarchyClassTargetSelector(cha));
  }

  /**
   * Not terribly efficient
   * 
   * @throws IllegalArgumentException if g1 is null
   * @throws IllegalArgumentException if g2 is null
   */
  @Deprecated
  public static <T> boolean areEqual(Graph<T> g1, Graph<T> g2) {
    if (g2 == null) {
      throw new IllegalArgumentException("g2 is null");
    }
    if (g1 == null) {
      throw new IllegalArgumentException("g1 is null");
    }
    if (g1.getNumberOfNodes() != g2.getNumberOfNodes()) {
      return false;
    }
    Set<T> n1 = setify(g1.iterator());
    Set<T> n2 = setify(g2.iterator());
    if (!n1.equals(n2)) {
      return false;
    }
    for (Iterator<T> it = n1.iterator(); it.hasNext();) {
      T x = it.next();
      if (g1.getSuccNodeCount(x) != g2.getSuccNodeCount(x)) {
        return false;
      }
      Set s1 = setify(g1.getSuccNodes(x));
      Set s2 = setify(g2.getSuccNodes(x));
      if (!s1.equals(s2)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Is g1 a subset of g2? Not terribly efficient
   * 
   * @throws IllegalArgumentException if g1 is null
   * @throws IllegalArgumentException if g2 is null
   */
  @Deprecated
  public static <T> boolean isSubset(Graph<T> g1, Graph<T> g2) {
    if (g2 == null) {
      throw new IllegalArgumentException("g2 is null");
    }
    if (g1 == null) {
      throw new IllegalArgumentException("g1 is null");
    }
    if (g1.getNumberOfNodes() > g2.getNumberOfNodes()) {
      return false;
    }
    Set<T> n1 = setify(g1.iterator());
    Set<T> n2 = setify(g2.iterator());
    if (!n2.containsAll(n1)) {
      return false;
    }
    for (Iterator<T> it = n1.iterator(); it.hasNext();) {
      T x = it.next();
      if (g1.getSuccNodeCount(x) > g2.getSuccNodeCount(x)) {
        return false;
      }
      Set<T> s1 = setify(g1.getSuccNodes(x));
      Set<T> s2 = setify(g2.getSuccNodes(x));
      if (!s2.containsAll(s1)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Modify an options object to include bypass logic as specified by a an XML file.
   * 
   * @throws IllegalArgumentException if scope is null
   * @throws IllegalArgumentException if cl is null
   * @throws IllegalArgumentException if options is null
   * @throws IllegalArgumentException if scope is null
   */
  public static void addBypassLogic(AnalysisOptions options, AnalysisScope scope, ClassLoader cl, String xmlFile,
      IClassHierarchy cha) throws IllegalArgumentException {
    if (scope == null) {
      throw new IllegalArgumentException("scope is null");
    }
    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    if (cl == null) {
      throw new IllegalArgumentException("cl is null");
    }
    if (cha == null) {
      throw new IllegalArgumentException("cha cannot be null");
    }

    InputStream s = cl.getResourceAsStream(xmlFile);
    XMLMethodSummaryReader summary = new XMLMethodSummaryReader(s, scope);

    MethodTargetSelector ms = new BypassMethodTargetSelector(options.getMethodTargetSelector(), summary.getSummaries(), summary
        .getIgnoredPackages(), cha);
    options.setSelector(ms);

    ClassTargetSelector cs = new BypassClassTargetSelector(options.getClassTargetSelector(), summary.getAllocatableClasses(), cha,
        cha.getLoader(scope.getLoader(Atom.findOrCreateUnicodeAtom("Synthetic"))));
    options.setSelector(cs);
  }

  /**
   * @return the Set of CGNodes in the call graph that are reachable without traversing any entrypoint node
   * @throws IllegalArgumentException if cg is null
   */
  @Deprecated
  public static Collection<CGNode> computeDarkEntrypointNodes(final CallGraph cg, final Collection<CGNode> entrypoints) {

    if (cg == null) {
      throw new IllegalArgumentException("cg is null");
    }
    final class DarkIterator extends SlowDFSDiscoverTimeIterator<CGNode> {

      private static final long serialVersionUID = -7554905808017614372L;

      // this is a yucky kludge .. purposely avoid calling a super() ctor to
      // avoid calling getConnected() before this call is fully initialized.
      DarkIterator() {
        init(cg, Collections.singleton(cg.getFakeRootNode()).iterator());
      }

      @Override
      public Iterator<CGNode> getConnected(CGNode N) {
        HashSet<CGNode> result = HashSetFactory.make(5);
        for (Iterator it = super.getConnected(N); it.hasNext();) {
          CGNode X = (CGNode) it.next();
          if (!entrypoints.contains(X)) {
            result.add(X);
          }
        }
        return result.iterator();
      }
    }

    HashSet<CGNode> result = HashSetFactory.make();
    for (DarkIterator D = new DarkIterator(); D.hasNext();) {
      CGNode N = D.next();
      result.add(N);
    }
    return result;
  }

  /**
   * @param scope
   * @param cha
   * @return set of all eligible Main classes in the class hierarchy
   * @throws IllegalArgumentException if scope is null
   */
  public static Iterable<Entrypoint> makeMainEntrypoints(AnalysisScope scope, IClassHierarchy cha) {
    if (scope == null) {
      throw new IllegalArgumentException("scope is null");
    }
    return makeMainEntrypoints(scope.getApplicationLoader(), cha);
  }

  public static Iterable<Entrypoint> makeMainEntrypoints(ClassLoaderReference clr, IClassHierarchy cha) {
    if (cha == null) {
      throw new IllegalArgumentException("cha is null");
    }
    final Atom mainMethod = Atom.findOrCreateAsciiAtom("main");
    final HashSet<Entrypoint> result = HashSetFactory.make();
    for (IClass klass : cha) {
      if (klass.getClassLoader().getReference().equals(clr)) {
        MethodReference mainRef = MethodReference.findOrCreate(klass.getReference(), mainMethod, Descriptor
            .findOrCreateUTF8("([Ljava/lang/String;)V"));
        IMethod m = klass.getMethod(mainRef.getSelector());
        if (m != null) {
          result.add(new DefaultEntrypoint(m, cha));
        }
      }
    }
    return new Iterable<Entrypoint>() {
      public Iterator<Entrypoint> iterator() {
        return result.iterator();
      }
    };
  }

  /**
   * @return Entrypoints object for a Main J2SE class
   */
  public static Iterable<Entrypoint> makeMainEntrypoints(AnalysisScope scope, final IClassHierarchy cha, String className) {
    return makeMainEntrypoints(scope, cha, new String[] { className });
  }

  public static Iterable<Entrypoint> makeMainEntrypoints(final AnalysisScope scope, final IClassHierarchy cha,
      final String[] classNames) {
    if (scope == null) {
      throw new IllegalArgumentException("scope is null");
    }
    return makeMainEntrypoints(scope.getApplicationLoader(), cha, classNames);
  }

  /**
   * @return Entrypoints for a set of J2SE Main classes
   * @throws IllegalArgumentException if classNames == null
   * @throws IllegalArgumentException if (classNames != null) and (0 < classNames.length) and (classNames[0] == null)
   * @throws IllegalArgumentException if classNames.length == 0
   */
  public static Iterable<Entrypoint> makeMainEntrypoints(final ClassLoaderReference loaderRef, final IClassHierarchy cha,
      final String[] classNames) throws IllegalArgumentException, IllegalArgumentException, IllegalArgumentException {

    if (classNames == null) {
      throw new IllegalArgumentException("classNames == null");
    }
    if (classNames.length == 0) {
      throw new IllegalArgumentException("classNames.length == 0");
    }
    if (classNames[0] == null && 0 < classNames.length) {
      throw new IllegalArgumentException("(0 < classNames.length) and (classNames[0] == null)");
    }

    for (int i = 0; i < classNames.length; i++) {
      if (classNames[i].indexOf("L") != 0) {
        throw new IllegalArgumentException("Expected class name to start with L " + classNames[i]);
      }
      if (classNames[i].indexOf(".") > 0) {
        Assertions.productionAssertion(false, "Expected class name formatted with /, not . " + classNames[i]);
      }
    }

    return new Iterable<Entrypoint>() {
      public Iterator<Entrypoint> iterator() {
        final Atom mainMethod = Atom.findOrCreateAsciiAtom("main");
        return new Iterator<Entrypoint>() {
          private int index = 0;

          public void remove() {
            Assertions.UNREACHABLE();
          }

          public boolean hasNext() {
            return index < classNames.length;
          }

          public Entrypoint next() {
            TypeReference T = TypeReference.findOrCreate(loaderRef, TypeName.string2TypeName(classNames[index++]));
            MethodReference mainRef = MethodReference.findOrCreate(T, mainMethod, Descriptor
                .findOrCreateUTF8("([Ljava/lang/String;)V"));
            return new DefaultEntrypoint(mainRef, cha);
          }
        };
      }
    };
  }

  /**
   * create a set holding the contents of an {@link Iterator}
   */
  public static <T> Set<T> setify(Iterator<? extends T> x) {
    if (x == null) {
      throw new IllegalArgumentException("Null x");
    }
    Set<T> y = HashSetFactory.make();
    while (x.hasNext()) {
      y.add(x.next());
    }
    return y;
  }

  /**
   * @param supG
   * @param subG
   * @throws IllegalArgumentException if subG is null
   * @throws IllegalArgumentException if supG is null
   */
  public static <T> void checkGraphSubset(Graph<T> supG, Graph<T> subG) {
    if (supG == null) {
      throw new IllegalArgumentException("supG is null");
    }
    if (subG == null) {
      throw new IllegalArgumentException("subG is null");
    }
    Set<T> nodeDiff = setify(subG.iterator());
    nodeDiff.removeAll(setify(supG.iterator()));
    if (!nodeDiff.isEmpty()) {
      System.err.println("supergraph: ");
      System.err.println(supG.toString());
      System.err.println("subgraph: ");
      System.err.println(subG.toString());
      System.err.println("nodeDiff: ");
      for (Iterator it = nodeDiff.iterator(); it.hasNext();) {
        System.err.println(it.next().toString());
      }
      Assertions.productionAssertion(nodeDiff.isEmpty(), "bad superset, see tracefile\n");
    }

    for (Iterator<? extends T> subNodes = subG.iterator(); subNodes.hasNext();) {
      T m = subNodes.next();

      Set<T> succDiff = setify(subG.getSuccNodes(m));
      succDiff.removeAll(setify(supG.getSuccNodes(m)));
      if (!succDiff.isEmpty()) {
        Assertions.productionAssertion(succDiff.isEmpty(), "bad superset for successors of " + m + ":" + succDiff);
      }

      Set<T> predDiff = setify(subG.getPredNodes(m));
      predDiff.removeAll(setify(supG.getPredNodes(m)));
      if (!predDiff.isEmpty()) {
        System.err.println("supergraph: ");
        System.err.println(supG.toString());
        System.err.println("subgraph: ");
        System.err.println(subG.toString());
        System.err.println("predDiff: ");
        for (Iterator it = predDiff.iterator(); it.hasNext();) {
          System.err.println(it.next().toString());
        }
        Assertions.UNREACHABLE("bad superset for predecessors of " + m + ":" + predDiff);
      }
    }
  }

  /**
   * @return an RTA Call Graph builder.
   * 
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   */
  public static CallGraphBuilder makeRTABuilder(AnalysisOptions options, AnalysisCache cache, IClassHierarchy cha,
      AnalysisScope scope) {

    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);

    return new BasicRTABuilder(cha, options, cache, null, null);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @return a 0-CFA Call Graph Builder.
   */
  public static SSAPropagationCallGraphBuilder makeZeroCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope) {
    return makeZeroCFABuilder(options, cache, cha, scope, null, null);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @param customSelector user-defined context selector, or null if none
   * @param customInterpreter user-defined context interpreter, or null if none
   * @return a 0-CFA Call Graph Builder.
   * @throws IllegalArgumentException if options is null
   */
  public static SSAPropagationCallGraphBuilder makeZeroCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope, ContextSelector customSelector, SSAContextInterpreter customInterpreter) {

    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);

    return ZeroXCFABuilder.make(cha, options, cache, customSelector, customInterpreter, options.getReflectionSpec(),
        ZeroXInstanceKeys.NONE);
  }

  /**
   * @return a 0-1-CFA Call Graph Builder.
   * 
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   */
  public static SSAPropagationCallGraphBuilder makeZeroOneCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope) {
    return makeZeroOneCFABuilder(options, cache, cha, scope, null, null);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @param customSelector user-defined context selector, or null if none
   * @param customInterpreter user-defined context interpreter, or null if none
   * @return a 0-1-CFA Call Graph Builder.
   * @throws IllegalArgumentException if options is null
   */
  public static SSAPropagationCallGraphBuilder makeVanillaZeroOneCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope, ContextSelector customSelector, SSAContextInterpreter customInterpreter) {

    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);

    return ZeroXCFABuilder.make(cha, options, cache, customSelector, customInterpreter, options.getReflectionSpec(),
        ZeroXInstanceKeys.ALLOCATIONS | ZeroXInstanceKeys.CONSTANT_SPECIFIC);
  }

  /**
   * @return a 0-1-CFA Call Graph Builder.
   * 
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   */
  public static SSAPropagationCallGraphBuilder makeVanillaZeroOneCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope) {
    return makeVanillaZeroOneCFABuilder(options, cache, cha, scope, null, null);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @param customSelector user-defined context selector, or null if none
   * @param customInterpreter user-defined context interpreter, or null if none
   * @return a 0-1-CFA Call Graph Builder.
   * @throws IllegalArgumentException if options is null
   */
  public static SSAPropagationCallGraphBuilder makeZeroOneCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope, ContextSelector customSelector, SSAContextInterpreter customInterpreter) {

    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);

    return ZeroXCFABuilder.make(cha, options, cache, customSelector, customInterpreter, options.getReflectionSpec(),
        ZeroXInstanceKeys.ALLOCATIONS | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_PRIMITIVE_HOLDERS
            | ZeroXInstanceKeys.SMUSH_STRINGS | ZeroXInstanceKeys.SMUSH_THROWABLES);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @return a 0-CFA Call Graph Builder augmented with extra logic for containers
   * @throws IllegalArgumentException if options is null
   */
  public static SSAPropagationCallGraphBuilder makeZeroContainerCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope) {

    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);
    ContextSelector appSelector = null;
    SSAContextInterpreter appInterpreter = null;

    return new ZeroXContainerCFABuilder(cha, options, cache, appSelector, appInterpreter, options.getReflectionSpec(),
        ZeroXInstanceKeys.NONE);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @return a 0-1-CFA Call Graph Builder augmented with extra logic for containers
   * @throws IllegalArgumentException if options is null
   */
  public static SSAPropagationCallGraphBuilder makeZeroOneContainerCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope) {

    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);
    ContextSelector appSelector = null;
    SSAContextInterpreter appInterpreter = null;

    return new ZeroXContainerCFABuilder(cha, options, cache, appSelector, appInterpreter, options.getReflectionSpec(),
        ZeroXInstanceKeys.ALLOCATIONS | ZeroXInstanceKeys.SMUSH_MANY | ZeroXInstanceKeys.SMUSH_PRIMITIVE_HOLDERS
            | ZeroXInstanceKeys.SMUSH_STRINGS | ZeroXInstanceKeys.SMUSH_THROWABLES);
  }

  /**
   * @param options options that govern call graph construction
   * @param cha governing class hierarchy
   * @param scope representation of the analysis scope
   * @return a 0-1-CFA Call Graph Builder augmented with extra logic for containers
   * @throws IllegalArgumentException if options is null
   */
  public static SSAPropagationCallGraphBuilder makeVanillaZeroOneContainerCFABuilder(AnalysisOptions options, AnalysisCache cache,
      IClassHierarchy cha, AnalysisScope scope) {

    if (options == null) {
      throw new IllegalArgumentException("options is null");
    }
    addDefaultSelectors(options, cha);
    addDefaultBypassLogic(options, scope, Util.class.getClassLoader(), cha);
    ContextSelector appSelector = null;
    SSAContextInterpreter appInterpreter = null;
    options.setUseConstantSpecificKeys(true);

    return new ZeroXContainerCFABuilder(cha, options, cache, appSelector, appInterpreter, options.getReflectionSpec(),
        ZeroXInstanceKeys.ALLOCATIONS);

  }

  public static void addDefaultBypassLogic(AnalysisOptions options, AnalysisScope scope, ClassLoader cl, IClassHierarchy cha) {
    addBypassLogic(options, scope, cl, nativeSpec, cha);
  }

}
