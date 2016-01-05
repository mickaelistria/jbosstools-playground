package org.jboss.tools.playground.scope.tests;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;

import org.eclipse.core.resources.ICommand;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.jboss.tools.playground.scope.ScopeValidator;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestScopeTest {
	
	@Parameters(name = "{0}")
	public static Collection<String> data() {
		return Arrays.asList(new String[] { "KOImport.java", "KOMember.java",  "KOParameter.java", "KOReturnType.java", "KOWildcardImport.java"} );
	}
	
	@Parameter
	public String fileToCheck;
	private static IProject project;
	
	@BeforeClass
	public static void provisionAndBuildTestProject() throws Exception {
		URL url = FileLocator.toFileURL(TestScopeTest.class.getClassLoader().getResource("testScope"));
		IProjectDescription description = ResourcesPlugin.getWorkspace().loadProjectDescription(new Path(url.getFile() + File.separator + IProjectDescription.DESCRIPTION_FILE_NAME));
		description.setLocation(new Path(url.getFile()));
		project = ResourcesPlugin.getWorkspace().getRoot().getProject(description.getName());
		project.create(description, null);
		project.open(null);
		//
		IProjectDescription desc = project.getDescription();
		ICommand[] commands = desc.getBuildSpec();
		boolean builderAlreadySetup = false;
		for (int i = 0; i < commands.length; ++i) {
			if (!builderAlreadySetup && commands[i].getBuilderName().equals(ScopeValidator.BUILDER_ID)) {
				builderAlreadySetup = true;
			}
		}
		if (!builderAlreadySetup) {
			ICommand command = desc.newCommand();
			command.setBuilderName(ScopeValidator.BUILDER_ID);
			ICommand[] nc = new ICommand[commands.length + 1];
			// Add it before other builders.
			System.arraycopy(commands, 0, nc, 0, commands.length);
			nc[nc.length - 1] = command;
			desc.setBuildSpec(nc);
			project.setDescription(desc, null);
		}
		
		project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());

	}

	@Test
	public void test() throws Exception {
		IFile file = project.getFolder("restrict/testScope").getFile(this.fileToCheck);
		IMarker[] markers = file.findMarkers(ScopeValidator.MARKER_TYPE, true, IResource.DEPTH_INFINITE);
		Assert.assertNotEquals("At least 1 marker should be found on " + file.getName(), 0, markers.length);
	}

}
