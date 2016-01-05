package org.jboss.tools.playground.scope;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IParent;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeReferenceMatch;
import org.eclipse.jdt.internal.core.search.matching.TypeReferencePattern;

public class ScopeValidator extends IncrementalProjectBuilder {

	private final class CreateMarkerForWrongScopeReferenceRequestor extends SearchRequestor {
		private ICompilationUnit compilationUnit;
		private Set<IClasspathEntry> relevantEntries;

		public CreateMarkerForWrongScopeReferenceRequestor(ICompilationUnit cu, Set<IClasspathEntry> relevantEntries) {
			this.compilationUnit = cu;
			this.relevantEntries = relevantEntries;
		}

		@Override
		public void acceptSearchMatch(SearchMatch match) throws CoreException {
			TypeReferenceMatch typeRefMatch = (TypeReferenceMatch) match;
			String type = null;
			if (typeRefMatch.getElement() instanceof IImportDeclaration) {
				type = ((IImportDeclaration)match.getElement()).getElementName().toString();
			} else {
				char[] substring = new char[match.getLength()];
				char[] content = match.getParticipant().getDocument(match.getResource().getFullPath().toString()).getCharContents();
				System.arraycopy(content, match.getOffset(), substring, 0, match.getLength());
				type = new String(substring).trim();
			}
			IType resolvedType = this.compilationUnit.getJavaProject().findType(type);
			if (resolvedType == null) {
				// unknown type. Skipping?
				return;
			}
			IPackageFragmentRoot resolvedTypePkgRoot = (IPackageFragmentRoot)resolvedType.getPackageFragment().getParent();
			for (IClasspathEntry entry : this.relevantEntries) {
				for (IPackageFragmentRoot root : compilationUnit.getJavaProject().findPackageFragmentRoots(entry)) {
					if (root.equals(resolvedTypePkgRoot)) {
						return;
					}
				}
			}
			IMarker marker = this.compilationUnit.getResource().createMarker(MARKER_TYPE);
			marker.setAttribute(IMarker.MESSAGE, "Type not in scope - " + type);
			marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
			marker.setAttribute(IMarker.CHAR_START, match.getOffset());
			marker.setAttribute(IMarker.CHAR_END, match.getOffset() + match.getLength());
			marker.setAttribute(IMarker.LINE_NUMBER, 1);
		}
	}

	private static final String BUNDLE_ID = "org.jboss.tools.playground.scope";
	public static final String BUILDER_ID = BUNDLE_ID + ".scopeBuilder";
	public static final String MARKER_TYPE = BUNDLE_ID + ".scope";
	private static final Object SCOPE_ATTRIBUTE = "scope";
	
	public ScopeValidator() {
		// TODO Auto-generated constructor stub
	}

	@Override
	protected IProject[] build(int kind, Map<String, String> args, IProgressMonitor monitor) throws CoreException {
		final IJavaProject javaProject = (IJavaProject) getProject().getNature(JavaCore.NATURE_ID);
		final Map<IPath, Set<IClasspathEntry>> classpathForSrcFolder= new HashMap<>();
		for (IClasspathEntry entry : javaProject.getRawClasspath()) {
			if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
				classpathForSrcFolder.put(entry.getPath(), new HashSet<>());
			}
		}
		boolean projectUsesScopes = false;
		for (IClasspathEntry entry : javaProject.getRawClasspath()) {
			boolean isScopedCPEntry = false;
			for (IClasspathAttribute att : entry.getExtraAttributes()) {
				String[] folders = att.getValue().split(",");
				if (SCOPE_ATTRIBUTE.equals(att.getName())) {
					isScopedCPEntry = true;
					projectUsesScopes = true;
					folders = att.getValue().split(",");
					for (String folder : folders) {
						classpathForSrcFolder.get(getProject().getFolder(folder).getFullPath()).add(entry);
					}
				} 
			}
			if (!isScopedCPEntry) {
				for (Entry<IPath, Set<IClasspathEntry>> scope : classpathForSrcFolder.entrySet()) {
					scope.getValue().add(entry);
				}
			}
		}
		
		if (!projectUsesScopes) {
			return new IProject[0];
		}

		Set<ICompilationUnit> units = new HashSet<ICompilationUnit>();
		if (getDelta(getProject()) != null && getDelta(getProject()).getResource() != javaProject.getResource()) {
			getDelta(getProject()).accept(delta -> {
				if (delta.getKind() == IResourceDelta.CHANGED) {
					ICompilationUnit cu = delta.getResource().getAdapter(ICompilationUnit.class);
					units.add(cu);
				}
				return false;
			});
		} else /* build whole project */ {
			Queue<IJavaElement> toProcess = new LinkedList<>();
			toProcess.addAll(Arrays.asList(javaProject.getPackageFragments()));
			while (!toProcess.isEmpty()) {
				IJavaElement current = toProcess.poll();
				if (current.getElementType() == IJavaElement.COMPILATION_UNIT) {
					units.add((ICompilationUnit)current);
				} else if (current instanceof IParent) {
					toProcess.addAll(Arrays.asList(((IParent)current).getChildren()));
				}
			}
		}
		
		final SearchEngine searchEngine = new SearchEngine(units.toArray(new ICompilationUnit[units.size()]));
		SearchPattern pattern = new TypeReferencePattern(null, null, SearchPattern.R_PATTERN_MATCH);
		for (ICompilationUnit cu : units) {
			IResource resource = cu.getResource();
			resource.deleteMarkers(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
			Set<IClasspathEntry> relevantEntries = null;
			for (Entry<IPath, Set<IClasspathEntry>> entry : classpathForSrcFolder.entrySet()) {
				if (entry.getKey().isPrefixOf(resource.getFullPath())) {
					relevantEntries = entry.getValue();
				}
			}
			if (relevantEntries.size() != javaProject.getRawClasspath().length) {
				SearchRequestor requestor = new CreateMarkerForWrongScopeReferenceRequestor(cu, relevantEntries);
				searchEngine.search(pattern,
						new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
						SearchEngine.createJavaSearchScope(new IJavaElement[] { cu }),
						requestor, monitor);
				//searchEngine.searchDeclarationsOfReferencedTypes(cu, requestor, monitor);
			}
		}
		return new IProject[] { getProject() };
	}

}
