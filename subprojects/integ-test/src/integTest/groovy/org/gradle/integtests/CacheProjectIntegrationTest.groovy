/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.integtests

import org.gradle.api.internal.artifacts.ivyservice.DefaultCacheLockingManager
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.groovy.scripts.UriScriptSource
import org.gradle.integtests.fixtures.AbstractIntegrationTest
import org.gradle.integtests.fixtures.MavenRepository
import org.gradle.test.fixtures.server.http.HttpServer
import org.gradle.util.GradleVersion
import org.gradle.util.TestFile
import org.junit.Before
import org.junit.Rule
import org.junit.Test

import static org.junit.Assert.assertEquals

/**
 * @author Hans Dockter
 */
public class CacheProjectIntegrationTest extends AbstractIntegrationTest {
    static final String TEST_FILE = "build/test.txt"

    @Rule public final HttpServer server = new HttpServer()

    TestFile projectDir
    TestFile userHomeDir
    TestFile buildFile
    TestFile propertiesFile
    TestFile classFile
    TestFile artifactsCache

    MavenRepository repo

    @Before
    public void setUp() {
        // Use own home dir so we don't blast the shared one when we run with -C rebuild
        distribution.requireOwnUserHomeDir()

        String version = GradleVersion.current().version
        projectDir = distribution.getTestDir().file("project")
        projectDir.mkdirs()
        userHomeDir = distribution.getUserHomeDir()
        buildFile = projectDir.file('build.gradle')
        ScriptSource source = new UriScriptSource("build file", buildFile)
        propertiesFile = userHomeDir.file("caches/$version/scripts/$source.className/ProjectScript/no_buildscript/cache.properties")
        classFile = userHomeDir.file("caches/$version/scripts/$source.className/ProjectScript/no_buildscript/classes/${source.className}.class")
        artifactsCache = projectDir.file(".gradle/$version/taskArtifacts/taskArtifacts.bin")

        def repoDir = file("repo")
        repo = maven(repoDir)
        server.allowGetOrHead("/repo", repo.rootDir)
        repo.module("commons-io", "commons-io", "1.4").publish()
        repo.module("commons-lang", "commons-lang", "2.6").publish()

        server.start()
    }

    @Test
    public void "caches compiled build script"() {
        createLargeBuildScript()
        testBuild("hello1", "Hello 1")
        TestFile.Snapshot classFileSnapshot = classFile.snapshot()

        testBuild("hello2", "Hello 2")
        classFile.assertHasNotChangedSince(classFileSnapshot)

        modifyLargeBuildScript()
        testBuild("newTask", "I am new")
        classFile.assertHasChangedSince(classFileSnapshot)
        classFileSnapshot = classFile.snapshot()

        testBuild("newTask", "I am new", "--recompile-scripts")
        classFile.assertHasChangedSince(classFileSnapshot)
    }

    @Test
    public void "caches incremental build state"() {
        createLargeBuildScript()
        testBuild("hello1", "Hello 1")
        TestFile.Snapshot artifactsCacheSnapshot = artifactsCache.snapshot()

        testBuild("hello1", "Hello 1")
        artifactsCache.assertHasNotChangedSince(artifactsCacheSnapshot)

        testBuild("hello2", "Hello 2")
        artifactsCache.assertHasChangedSince(artifactsCacheSnapshot)
        artifactsCacheSnapshot = artifactsCache.snapshot()

        testBuild("hello2", "Hello 2", "-rerun-tasks")
        artifactsCache.assertHasChangedSince(artifactsCacheSnapshot)
    }

    @Test
    public void "does not rebuild artifact cache when run with --recompile-scripts"() {
        createLargeBuildScript()
        testBuild("hello1", "Hello 1")

        TestFile dependenciesCache = findDependencyCacheDir()
        assert dependenciesCache.isDirectory() && dependenciesCache.listFiles().length > 0

        modifyLargeBuildScript()
        testBuild("newTask", "I am new", "--recompile-scripts")
        assert dependenciesCache.isDirectory() && dependenciesCache.listFiles().length > 0
    }

    @Test
    public void "does not rebuild artifact cache when run with --cache rebuild"() {
        createLargeBuildScript()
        testBuild("hello1", "Hello 1")

        TestFile dependenciesCache = findDependencyCacheDir()
        assert dependenciesCache.isDirectory() && dependenciesCache.listFiles().length > 0

        modifyLargeBuildScript()
        testBuild("newTask", "I am new", "-Crebuild")
        assert dependenciesCache.isDirectory() && dependenciesCache.listFiles().length > 0
    }

    @Test
    public void "does not rebuild artifact cache when run with --rerun-tasks"() {
        createLargeBuildScript()
        testBuild("hello1", "Hello 1")

        TestFile dependenciesCache = findDependencyCacheDir()
        assert dependenciesCache.isDirectory() && dependenciesCache.listFiles().length > 0

        modifyLargeBuildScript()
        testBuild("newTask", "I am new", "--rerun-tasks")
        assert dependenciesCache.isDirectory() && dependenciesCache.listFiles().length > 0
    }

    private TestFile findDependencyCacheDir() {
        def cacheVersion = DefaultCacheLockingManager.CACHE_LAYOUT_VERSION
        def resolverArtifactCache = new TestFile(userHomeDir.file("caches/artifacts-${cacheVersion}/filestore"))
        return resolverArtifactCache.file("commons-io/commons-io/")
    }

    private def testBuild(String taskName, String expected, String... args) {
        executer.inDirectory(projectDir).withTasks(taskName).withArguments(args).run()
        assertEquals(expected, projectDir.file(TEST_FILE).text)
        classFile.assertIsFile()
        propertiesFile.assertIsFile()
        artifactsCache.assertIsFile()
    }

    // We once ran into a cache problem under windows, which was not reproducible with small build scripts. Therefore we
    // create a larger one here.

    def createLargeBuildScript() {
        File buildFile = projectDir.file('build.gradle')
        String content = """
repositories {
    maven{
        url "http://localhost:${server.port}/repo"
    }
}
configurations { compile }
dependencies { compile 'commons-io:commons-io:1.4@jar' }
"""

        50.times {i ->
            content += """
task 'hello$i' {
    File file = file('$TEST_FILE')
    outputs.file file
    doLast {
        configurations.compile.resolve()
        file.parentFile.mkdirs()
        file.write('Hello $i')
    }
}

void someMethod$i() {
    println('Some message')
}

"""
        }
        buildFile.write(content)
    }

    def void modifyLargeBuildScript() {
        File buildFile = projectDir.file('build.gradle')
        String newContent = buildFile.text + """
configurations { other }
dependencies { other 'commons-lang:commons-lang:2.6@jar' }

task newTask {
    File file = file('$TEST_FILE')
    outputs.file file
    doLast {
        configurations.other.resolve()
        file.parentFile.mkdirs()
        file.write('I am new')
    }
}
"""
        buildFile.write(newContent)
    }
}
