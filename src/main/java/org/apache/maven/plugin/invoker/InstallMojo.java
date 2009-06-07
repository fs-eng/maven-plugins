package org.apache.maven.plugin.invoker;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.metadata.ArtifactMetadata;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.codehaus.plexus.util.FileUtils;

/**
 * Installs the project artifacts of the main build into the local repository as a preparation to run the sub projects.
 * More precisely, all artifacts of the project itself, all its locally reachable parent POMs and all its dependencies
 * from the reactor will be installed to the local repository.
 * 
 * @goal install
 * @phase pre-integration-test
 * @requiresDependencyResolution runtime
 * @since 1.2
 * @author Paul Gier
 * @author Benjamin Bentmann
 * @version $Id$
 */
public class InstallMojo
    extends AbstractMojo
{

    /**
     * Maven artifact install component to copy artifacts to the local repository.
     * 
     * @component
     */
    private ArtifactInstaller installer;

    /**
     * The component used to create artifacts.
     * 
     * @component
     */
    private ArtifactFactory artifactFactory;

    /**
     * The component used to create artifacts.
     * 
     * @component
     */
    private ArtifactRepositoryFactory repositoryFactory;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * The path to the local repository into which the project artifacts should be installed for the integration tests.
     * If not set, the regular local repository will be used. To prevent soiling of your regular local repository with
     * possibly broken artifacts, it is strongly recommended to use an isolated repository for the integration tests
     * (e.g. <code>${project.build.directory}/it-repo</code>).
     * 
     * @parameter expression="${invoker.localRepositoryPath}"
     */
    private File localRepositoryPath;

    /**
     * The current Maven project.
     * 
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The set of Maven projects in the reactor build.
     * 
     * @parameter default-value="${reactorProjects}"
     * @readonly
     */
    private Collection reactorProjects;

    /**
     * A flag used to disable the installation procedure. This is primarily intended for usage from the command line to
     * occasionally adjust the build.
     * 
     * @parameter expression="${invoker.skip}" default-value="false"
     * @since 1.4
     */
    private boolean skipInstallation;

    /**
     * The identifiers of already installed artifacts, used to avoid multiple installation of the same artifact.
     */
    private Collection installedArtifacts;

    /**
     * Performs this mojo's tasks.
     * 
     * @throws MojoExecutionException If the artifacts could not be installed.
     */
    public void execute()
        throws MojoExecutionException
    {
        if ( skipInstallation )
        {
            getLog().info( "Skipping artifact installation per configuration." );
            return;
        }

        ArtifactRepository testRepository = createTestRepository();

        installedArtifacts = new HashSet();

        installProjectArtifacts( project, testRepository );
        installProjectParents( project, testRepository );
        installProjectDependencies( project, reactorProjects, testRepository );
    }

    /**
     * Creates the local repository for the integration tests. If the user specified a custom repository location, the
     * custom repository will have the same identifier, layout and policies as the real local repository. That means
     * apart from the location, the custom repository will be indistinguishable from the real repository such that its
     * usage is transparent to the integration tests.
     * 
     * @return The local repository for the integration tests, never <code>null</code>.
     * @throws MojoExecutionException If the repository could not be created.
     */
    private ArtifactRepository createTestRepository()
        throws MojoExecutionException
    {
        ArtifactRepository testRepository = localRepository;

        if ( localRepositoryPath != null )
        {
            try
            {
                if ( !localRepositoryPath.exists() && !localRepositoryPath.mkdirs() )
                {
                    throw new IOException( "Failed to create directory: " + localRepositoryPath );
                }

                testRepository =
                    repositoryFactory.createArtifactRepository( localRepository.getId(),
                                                                localRepositoryPath.toURL().toExternalForm(),
                                                                localRepository.getLayout(),
                                                                localRepository.getSnapshots(),
                                                                localRepository.getReleases() );
            }
            catch ( Exception e )
            {
                throw new MojoExecutionException( "Failed to create local repository: " + localRepositoryPath, e );
            }
        }

        return testRepository;
    }

    /**
     * Installs the specified artifact to the local repository. Note: This method should only be used for artifacts that
     * originate from the current (reactor) build. Artifacts that have been grabbed from the user's local repository
     * should be installed to the test repository via {@link #stageArtifact(File, Artifact, ArtifactRepository)}.
     * 
     * @param file The file associated with the artifact, must not be <code>null</code>. This is in most cases the value
     *            of <code>artifact.getFile()</code> with the exception of the main artifact from a project with
     *            packaging "pom". Projects with packaging "pom" have no main artifact file. They have however artifact
     *            metadata (e.g. site descriptors) which needs to be installed.
     * @param artifact The artifact to install, must not be <code>null</code>.
     * @param testRepository The local repository to install the artifact to, must not be <code>null</code>.
     * @throws MojoExecutionException If the artifact could not be installed (e.g. has no associated file).
     */
    private void installArtifact( File file, Artifact artifact, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        try
        {
            if ( file == null )
            {
                throw new IllegalStateException( "Artifact has no associated file: " + file );
            }
            if ( !file.isFile() )
            {
                throw new IllegalStateException( "Artifact is not fully assembled: " + file );
            }

            if ( installedArtifacts.add( artifact.getId() ) )
            {
                installer.install( file, artifact, testRepository );
            }
            else
            {
                getLog().debug( "Not re-installing " + artifact + ", " + file );
            }
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to install artifact: " + artifact, e );
        }
    }

    /**
     * Installs the specified artifact to the local repository. This method serves basically the same purpose as
     * {@link #installArtifact(File, Artifact, ArtifactRepository)} but is meant for artifacts that have been resolved
     * from the user's local repository (and not the current build outputs). The subtle difference here is that
     * artifacts from the repository have already undergone transformations and these manipulations should not be redone
     * by the artifact installer. For this reason, this method performs plain copy operations to install the artifacts.
     * 
     * @param file The file associated with the artifact, must not be <code>null</code>.
     * @param artifact The artifact to install, must not be <code>null</code>.
     * @param testRepository The local repository to install the artifact to, must not be <code>null</code>.
     * @throws MojoExecutionException If the artifact could not be installed (e.g. has no associated file).
     */
    private void stageArtifact( File file, Artifact artifact, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        try
        {
            if ( file == null )
            {
                throw new IllegalStateException( "Artifact has no associated file: " + file );
            }
            if ( !file.isFile() )
            {
                throw new IllegalStateException( "Artifact is not fully assembled: " + file );
            }

            if ( installedArtifacts.add( artifact.getId() ) )
            {
                File destination = new File( testRepository.getBasedir(), testRepository.pathOf( artifact ) );

                getLog().debug( "Installing " + file + " to " + destination );

                FileUtils.copyFile( file, destination );

                for ( Iterator it = artifact.getMetadataList().iterator(); it.hasNext(); )
                {
                    ArtifactMetadata metadata = (ArtifactMetadata) it.next();
                    metadata.storeInLocalRepository( testRepository, testRepository );
                }
            }
            else
            {
                getLog().debug( "Not re-installing " + artifact + ", " + file );
            }
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to stage artifact: " + artifact, e );
        }
    }

    /**
     * Installs the main artifact and any attached artifacts of the specified project to the local repository.
     * 
     * @param mvnProject The project whose artifacts should be installed, must not be <code>null</code>.
     * @param testRepository The local repository to install the artifacts to, must not be <code>null</code>.
     * @throws MojoExecutionException If any artifact could not be installed.
     */
    private void installProjectArtifacts( MavenProject mvnProject, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        try
        {
            // Install POM (usually attached as metadata but that happens only as a side effect of the Install Plugin)
            installProjectPom( mvnProject, testRepository );

            // Install the main project artifact (if the project has one, e.g. has no "pom" packaging)
            Artifact mainArtifact = mvnProject.getArtifact();
            if ( mainArtifact.getFile() != null )
            {
                installArtifact( mainArtifact.getFile(), mainArtifact, testRepository );
            }

            // Install any attached project artifacts
            Collection attachedArtifacts = mvnProject.getAttachedArtifacts();
            for ( Iterator artifactIter = attachedArtifacts.iterator(); artifactIter.hasNext(); )
            {
                Artifact attachedArtifact = (Artifact) artifactIter.next();
                installArtifact( attachedArtifact.getFile(), attachedArtifact, testRepository );
            }
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to install project artifacts: " + mvnProject, e );
        }
    }

    /**
     * Installs the (locally reachable) parent POMs of the specified project to the local repository. The parent POMs
     * from the reactor must be installed or the forked IT builds will fail when using a clean repository.
     * 
     * @param mvnProject The project whose parent POMs should be installed, must not be <code>null</code>.
     * @param testRepository The local repository to install the POMs to, must not be <code>null</code>.
     * @throws MojoExecutionException If any POM could not be installed.
     */
    private void installProjectParents( MavenProject mvnProject, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        try
        {
            for ( MavenProject parent = mvnProject.getParent(); parent != null; parent = parent.getParent() )
            {
                if ( parent.getFile() == null )
                {
                    stageParentPoms( parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), testRepository );
                    break;
                }
                installProjectPom( parent, testRepository );
            }
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to install project parents: " + mvnProject, e );
        }
    }

    /**
     * Installs the POM of the specified project to the local repository.
     * 
     * @param mvnProject The project whose POM should be installed, must not be <code>null</code>.
     * @param testRepository The local repository to install the POM to, must not be <code>null</code>.
     * @throws MojoExecutionException If the POM could not be installed.
     */
    private void installProjectPom( MavenProject mvnProject, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        try
        {
            Artifact pomArtifact = null;
            if ( "pom".equals( mvnProject.getPackaging() ) )
            {
                pomArtifact = mvnProject.getArtifact();
            }
            if ( pomArtifact == null )
            {
                pomArtifact =
                    artifactFactory.createProjectArtifact( mvnProject.getGroupId(), mvnProject.getArtifactId(),
                                                           mvnProject.getVersion() );
            }
            installArtifact( mvnProject.getFile(), pomArtifact, testRepository );
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to install POM: " + mvnProject, e );
        }
    }

    /**
     * Installs the dependent projects from the reactor to the local repository. The dependencies on other modules from
     * the reactor must be installed or the forked IT builds will fail when using a clean repository.
     * 
     * @param mvnProject The project whose dependent projects should be installed, must not be <code>null</code>.
     * @param reactorProjects The set of projects in the reactor build, must not be <code>null</code>.
     * @param testRepository The local repository to install the POMs to, must not be <code>null</code>.
     * @throws MojoExecutionException If any dependency could not be installed.
     */
    private void installProjectDependencies( MavenProject mvnProject, Collection reactorProjects,
                                             ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        // index available reactor projects
        Map projects = new HashMap();
        for ( Iterator it = reactorProjects.iterator(); it.hasNext(); )
        {
            MavenProject reactorProject = (MavenProject) it.next();
            String id = reactorProject.getGroupId() + ':' + reactorProject.getArtifactId();
            projects.put( id, reactorProject );
        }

        // collect transitive dependencies (even those that don't contribute to the class path like POMs)
        Collection artifacts = mvnProject.getArtifacts();
        Collection dependencies = new LinkedHashSet();
        for ( Iterator it = artifacts.iterator(); it.hasNext(); )
        {
            Artifact artifact = (Artifact) it.next();
            String id = ArtifactUtils.versionlessKey( artifact );
            dependencies.add( id );
        }

        // install dependencies
        try
        {
            // install dependencies from reactor
            for ( Iterator it = dependencies.iterator(); it.hasNext(); )
            {
                String id = (String) it.next();
                MavenProject requiredProject = (MavenProject) projects.remove( id );
                if ( requiredProject != null )
                {
                    it.remove();
                    installProjectArtifacts( requiredProject, testRepository );
                    installProjectParents( requiredProject, testRepository );
                }
            }

            // install remaining dependencies from local repository
            for ( Iterator it = artifacts.iterator(); it.hasNext(); )
            {
                Artifact artifact = (Artifact) it.next();
                String id = ArtifactUtils.versionlessKey( artifact );

                if ( dependencies.contains( id ) )
                {
                    // workaround for MNG-2961 to ensure the base version does not contain a timestamp
                    artifact.isSnapshot();

                    Artifact depArtifact =
                        artifactFactory.createArtifactWithClassifier( artifact.getGroupId(),
                                                                      artifact.getArtifactId(),
                                                                      artifact.getBaseVersion(), artifact.getType(),
                                                                      artifact.getClassifier() );

                    File artifactFile = artifact.getFile();

                    Artifact pomArtifact =
                        artifactFactory.createArtifact( artifact.getGroupId(), artifact.getArtifactId(),
                                                        artifact.getBaseVersion(), null, "pom" );

                    File pomFile = new File( localRepository.getBasedir(), localRepository.pathOf( pomArtifact ) );
                    if ( pomFile.isFile() )
                    {
                        if ( !pomFile.equals( artifactFile ) )
                        {
                            depArtifact.addMetadata( new ProjectArtifactMetadata( depArtifact, pomFile ) );
                        }
                        stageParentPoms( pomFile, testRepository );
                    }

                    stageArtifact( artifactFile, depArtifact, testRepository );
                }
            }
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to install project dependencies: " + mvnProject, e );
        }
    }

    /**
     * Installs all parent POMs of the specified POM file that are available in the local repository.
     * 
     * @param pomFile The path to the POM file whose parents should be installed, must not be <code>null</code>.
     * @param testRepository The local repository to install the POMs to, must not be <code>null</code>.
     * @throws MojoExecutionException If any (existing) parent POM could not be installed.
     */
    private void stageParentPoms( File pomFile, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        Model model = PomUtils.loadPom( pomFile );
        Parent parent = model.getParent();
        if ( parent != null )
        {
            stageParentPoms( parent.getGroupId(), parent.getArtifactId(), parent.getVersion(), testRepository );
        }
    }

    /**
     * Installs the specified POM and all its parent POMs to the local repository.
     * 
     * @param groupId The group id of the POM which should be installed, must not be <code>null</code>.
     * @param artifactId The artifact id of the POM which should be installed, must not be <code>null</code>.
     * @param version The version of the POM which should be installed, must not be <code>null</code>.
     * @param testRepository The local repository to install the POMs to, must not be <code>null</code>.
     * @throws MojoExecutionException If any (existing) parent POM could not be installed.
     */
    private void stageParentPoms( String groupId, String artifactId, String version, ArtifactRepository testRepository )
        throws MojoExecutionException
    {
        Artifact pomArtifact = artifactFactory.createProjectArtifact( groupId, artifactId, version );

        if ( installedArtifacts.contains( pomArtifact.getId() ) )
        {
            getLog().debug( "Not re-installing " + pomArtifact );
            return;
        }

        File pomFile = new File( localRepository.getBasedir(), localRepository.pathOf( pomArtifact ) );
        if ( pomFile.isFile() )
        {
            stageArtifact( pomFile, pomArtifact, testRepository );
            stageParentPoms( pomFile, testRepository );
        }
    }

}
