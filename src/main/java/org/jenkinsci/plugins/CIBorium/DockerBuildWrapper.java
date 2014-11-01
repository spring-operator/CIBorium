/*
 * Copyright (C) 2013-2014 Pivotal Software, Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Pivotal Software, Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.jenkinsci.plugins.CIBorium;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.remoting.Channel;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The {@link org.jenkinsci.plugins.CIBorium.DockerBuildWrapper} attempts to run all build steps
 * inside new docker containers.  Because of the way jenkins works, each build step is only executed
 * once the last build step was successful.  Because of this behavior, this wrapper will put each
 * build step in its own docker image.
 * <p/>
 * Example (using dsl plugin's syntax):
 * <p/>
 * Lets say you have two shell build steps
 * <p/>
 * {@code shell("touch /tmp/file")}
 * {@code shell("ls /tmp/file")}
 * <p/>
 * The second build step will fail, since it is run in a different container than the first build
 * step.
 * <p/>
 * To make jenkins plugins work off the data generated by build steps, the jenkins workspace
 * is mounted inside the docker container.  This means any change inside the workspace will
 * be discoverable to any jenkins plugin or following build step.
 */
public final class DockerBuildWrapper extends BuildWrapper {

  /**
   * User defined docker image.  If no image is defined, a auto-generated one should be used.
   */
  private String dockerImage;

  /**
   * User defines what environment variables get added to the container.  This should be a string
   * serialized list, so tokenization is required before using; {@link CIBorium#tokenize(CharSequence)}.
   */
  private String includeEnvironment;

  /**
   * Extra options to send to docker.  This should be a string serialized list, so tokenization is
   * required before using; {@link CIBorium#tokenize(CharSequence)}.
   */
  private String dockerOpts;

  @DataBoundConstructor
  public DockerBuildWrapper(final String dockerImage,
                            final String includeEnvironment,
                            final String dockerOpts) {
    this.dockerImage = dockerImage;
    this.includeEnvironment = includeEnvironment;
    this.dockerOpts = dockerOpts;
  }

  /**
   * Jenkins will serialize this object to config.xml.  When loading from there {@link com.thoughtworks.xstream.XStreamer} is used.
   * This will load the object from XML using Java's Serialization.  That means that if you change the state of the object
   * between versions, then you need to handle migration from XML to code.  This method can be used for that.
   *
   * @return this or a new DockerBuildWrapper object
   * @throws IOException if unable to create object
   */
  public Object readResolve() throws IOException {
    return this;
  }

  /**
   * Get the current value of dockerImage. This may be null.
   */
  public String getDockerImage() {
    return dockerImage;
  }

  /**
   * Get a unparsed list of environment variables to include. This may be null.
   */
  public String getIncludeEnvironment() {
    return includeEnvironment;
  }

  /**
   * Get unparsed list of options to send to docker. This may be null.
   */
  public String getDockerOpts() {
    return dockerOpts;
  }

  /**
   * Gets the docker image name.  If the value is null, then the {@code defaultValue} will be
   * returned.
   */
  private String getDockerImageOr(final String defaultValue) {
    if (Strings.isNullOrEmpty(dockerImage)) return defaultValue;
    else return dockerImage;
  }

  @Override
  public Launcher decorateLauncher(final AbstractBuild build, final Launcher launcher,
                                   final BuildListener listener)
      throws IOException, InterruptedException, Run.RunnerAbortedException {
    final EnvVars env = build.getEnvironment(listener);
    final String[] dockerCmd = dockerRun(build, env);
    final String name = CIBorium.getName(build);
    return partialDecorateByPrefix(launcher, dockerCmd, PARTIAL_PREFIX, name);
  }

  /**
   * Returns true if the proc does not contain a docker command.
   */
  private static final Predicate<Launcher.ProcStarter> SKIP_DOCKER_COMMANDS = new Predicate<Launcher.ProcStarter>() {
    public boolean apply(final Launcher.ProcStarter input) {
      //TODO any command with docker in it will currently get ignored... example: echo docker.
      // Make this smarter (first non sudo option != docker?
      return !input.cmds().contains("docker");
    }
  };

  /**
   * Returns true if the proc is not docker build.
   */
  private static final Predicate<Launcher.ProcStarter> SKIP_DOCKER_BUILD_COMMANDS = new Predicate<Launcher.ProcStarter>() {
    public boolean apply(final Launcher.ProcStarter input) {
      //TODO any command with docker in it will currently get ignored... example: echo docker.
      // Make this smarter (first non sudo option != docker?
      final List<String> cmds = input.cmds();

      return !(cmds.size() == 3 && cmds.get(0).equals("/bin/sh") && cmds.get(2).startsWith("docker"));
    }
  };

  /**
   * Returns true if the proc does not contain a docker command, and is not null.
   */
  private static final Predicate<Launcher.ProcStarter> PARTIAL_PREFIX =
      Predicates.and(Predicates.notNull(), SKIP_DOCKER_COMMANDS, SKIP_DOCKER_BUILD_COMMANDS);

  /**
   * Fork of {@link Launcher#decorateByPrefix(String...)} adding the ability to filter commands to prefix.
   */
  private Launcher partialDecorateByPrefix(final Launcher outer,
                                           final String[] prefix,
                                           final Predicate<Launcher.ProcStarter> shouldApply,
                                           final String containerName) {
    return new Launcher(outer) {
      @Override
      public boolean isUnix() {
        return outer.isUnix();
      }

      @Override
      public Proc launch(ProcStarter starter) throws IOException {
        if (shouldApply.apply(starter)) {
          log("Running with docker command: " + Arrays.toString(prefix));
          starter.cmds().addAll(0, Arrays.asList(prefix));
          if (starter.masks() != null) {
            starter.masks(prefix(starter.masks()));
          }
        }
        return outer.launch(starter);
      }

      @Override
      public Channel launchChannel(final String[] cmd, final OutputStream out,
                                   final FilePath workDir, final Map<String, String> envVars)
          throws IOException, InterruptedException {
        return outer.launchChannel(prefix(cmd), out, workDir, envVars);
      }

      @Override
      public void kill(final Map<String, String> modelEnvVars)
          throws IOException, InterruptedException {
        outer.kill(modelEnvVars);
      }

      private void log(final String msg) {
        listener.getLogger().println(msg);
      }

      /**
       * Creates a new list of commands, but with the prefix in the front.  This is basically the
       * same as prefix cons args.
       * <p />
       * {@code prefix ::: args}
       */
      private String[] prefix(String[] args) {
        String[] newArgs = new String[args.length + prefix.length];
        System.arraycopy(prefix, 0, newArgs, 0, prefix.length);
        System.arraycopy(args, 0, newArgs, prefix.length, args.length);
        return newArgs;
      }

      /**
       * Creates a new array of size {@code args.length + prefix.length}.  This list
       * will have args at the beginning, then false for the rest.  This is needed to make sure
       * that the command's masks line up with the number of commands.
       */
      private boolean[] prefix(boolean[] args) {
        boolean[] newArgs = new boolean[args.length + prefix.length];
        System.arraycopy(args, 0, newArgs, prefix.length, args.length);
        return newArgs;
      }
    };
  }

  /**
   * Generates the command used to isolate the build command within docker.
   */
  private String[] dockerRun(final AbstractBuild build, final EnvVars env) throws IOException, InterruptedException {
    final String imageName = getDockerImageOr(CIBorium.getImageName(build));
    final String name = CIBorium.getName(build);
    //TODO find a way to get this at decorateLauncher call.  That way jenkins output is clear what is mounted
    final String workspace = "$WORKSPACE";
    final String tmpDir = System.getProperty("java.io.tmpdir");
    final List<String> cmd = Lists.newArrayList(
        "docker", "run", "-i", // send output to stdout
        "--rm", // clean up container once done
        "--name", name, // set the container name based off the build
        "-w", workspace, // create the workspace in the docker container.  This is needed for mounting workspace
        // mount the workspace in the docker container, so reporter plugins don't need to run in the container
        "-v", volume(workspace),
        // mount tmp since jenkins generates the script there for running
        "-v", volume(tmpDir),
        // set the hostname to be the same as the caller
        "-h", CIBorium.getHostname(build.getBuiltOn())
    );
    // add jenkins environment to docker
    //TODO cache this
    final List<String> include = CIBorium.tokenize(includeEnvironment);
    for (final Map.Entry<String, String> e : env.entrySet()) {
      if (include.contains(e.getKey())) {
        cmd.add("-e");
        cmd.add(environment(e));
      }
    }

    // add any extra opts into the command
    final List<String> extraOpts = CIBorium.tokenize(dockerOpts);
    cmd.addAll(extraOpts);

    //TODO WORKSPACE is not set at this point in time... how can I depend on w/e sets it?
    cmd.add("-e");
    cmd.add("WORKSPACE=$WORKSPACE");

    cmd.add(imageName);
    return cmd.toArray(new String[0]);
  }

  /**
   * Encodes the path in docker's volume syntax.
   */
  private static String volume(final String path) {
    return path + ":" + path;
  }

  /**
   * Encodes the entry in docker's environment syntax.
   */
  private static String environment(final Map.Entry<String, String> e) {
    return e.getKey() + "=" + e.getValue();
  }

  @Override
  public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener)
      throws IOException, InterruptedException {
    return new Environment() {
      @Override
      public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
        String containerName = CIBorium.getName(build);
        listener.getLogger().println("Job is done; attempting to cleanup container by name: " + containerName);

        // go to the node that ran the build and kill the process if its still running
        // need to do this for when the user manually kills the job
        Launcher launcher = build.getBuiltOn().createLauncher(listener);
        launcher.decorateFor(build.getBuiltOn());

        Launcher.ProcStarter ps = launcher.launch().cmds(Lists.newArrayList(
            "docker", "kill", containerName
        )).readStdout().readStderr();

        // print output to listener
        // why createLauncher needs a listener and doesn't do this for me is unknown.
        Proc proc = ps.start();
        int status = proc.join();
        ByteStreams.copy(proc.getStdout(), listener.getLogger());
        ByteStreams.copy(proc.getStderr(), listener.getLogger());

        listener.getLogger().println("Run docker kill against container " + containerName + "; got status " + status);

        // remove the container
        ps = launcher.launch().cmds(Lists.newArrayList(
            "docker", "rm", containerName
        )).readStdout().readStderr();

        // print output to listener
        // why createLauncher needs a listener and doesn't do this for me is unknown.
        proc = ps.start();
        status = proc.join();
        ByteStreams.copy(proc.getStdout(), listener.getLogger());
        ByteStreams.copy(proc.getStderr(), listener.getLogger());

        listener.getLogger().println("Run docker rm against container " + containerName + "; got status " + status);
        return super.tearDown(build, listener);
      }
    };
  }

  @Extension
  public static class DockerBuildWrapperDescriptor extends BuildWrapperDescriptor {

    @Override
    public boolean isApplicable(final AbstractProject<?, ?> job) {
      return FreeStyleProject.class.isAssignableFrom(job.getClass());
    }

    @Override
    public String getDisplayName() {
      return "Docker Environment";
    }
  }
}
