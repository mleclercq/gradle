/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.internal;

import groovy.lang.Closure;
import groovy.lang.MissingPropertyException;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.gradle.api.*;
import org.gradle.api.internal.file.TemporaryFileProvider;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.*;
import org.gradle.api.internal.tasks.execution.TaskValidator;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.logging.LoggingManager;
import org.gradle.api.plugins.Convention;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.specs.AndSpec;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskInputs;
import org.gradle.api.tasks.TaskInstantiationException;
import org.gradle.api.tasks.TaskState;
import org.gradle.internal.Factory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.logging.LoggingManagerInternal;
import org.gradle.logging.StandardOutputCapture;
import org.gradle.util.ConfigureUtil;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * @author Hans Dockter
 */
public abstract class AbstractTask implements TaskInternal, DynamicObjectAware {
    private static Logger buildLogger = Logging.getLogger(Task.class);
    private static ThreadLocal<TaskInfo> nextInstance = new ThreadLocal<TaskInfo>();
    private ProjectInternal project;

    private String name;

    private List<Action<? super Task>> actions = new ArrayList<Action<? super Task>>();

    private String path;

    private boolean enabled = true;

    private DefaultTaskDependency dependencies;

    private ExtensibleDynamicObject extensibleDynamicObject;

    private String description;

    private String group;

    private AndSpec<Task> onlyIfSpec = new AndSpec<Task>(createNewOnlyIfSpec());

    private final TaskOutputsInternal outputs;

    private final TaskInputs inputs;

    private TaskExecuter executer;

    private final ServiceRegistry services;

    private final TaskStateInternal state;

    private final LoggingManagerInternal loggingManager;

    private List<TaskValidator> validators = new ArrayList<TaskValidator>();
    private boolean nagUser = true;

    protected AbstractTask() {
        this(taskInfo());
    }

    private static TaskInfo taskInfo() {
        return nextInstance.get();
    }

    private AbstractTask(TaskInfo taskInfo) {
        if (taskInfo == null) {
            throw new TaskInstantiationException(String.format("Task of type '%s' has been instantiated directly which is not supported. Tasks can only be created using the DSL.", getClass().getName()));
        }

        this.project = taskInfo.project;
        this.name = taskInfo.name;
        assert project != null;
        assert name != null;
        path = project.absoluteProjectPath(name);
        state = new TaskStateInternal(toString());
        dependencies = new DefaultTaskDependency(project.getTasks());
        services = project.getServices().createFor(this);
        extensibleDynamicObject = new ExtensibleDynamicObject(this, getServices().get(Instantiator.class));
        outputs = services.get(TaskOutputsInternal.class);
        inputs = services.get(TaskInputs.class);
        executer = services.get(TaskExecuter.class);
        loggingManager = services.get(LoggingManagerInternal.class);
    }

    public static <T extends Task> T injectIntoNewInstance(ProjectInternal project, String name, Callable<T> factory) {
        nextInstance.set(new TaskInfo(project, name));
        try {
            try {
                return factory.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            nextInstance.set(null);
        }
    }

    public TaskState getState() {
        return state;
    }

    public AntBuilder getAnt() {
        return project.getAnt();
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = (ProjectInternal) project;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Action<? super Task>> getActions() {
        return new TaskStateAwareList(actions);
    }

    public void setActions(final List<Action<? super Task>> actions) {
        nagIfTaskNotInConfigurableState("Task.setActions(Actions<Task>)");
        nagUser = false;
        try {
            deleteAllActions();
            for (Action<? super Task> action : actions) {
                doLast(action);
            }
        } finally {
            nagUser = true;
        }
    }

    public TaskDependencyInternal getTaskDependencies() {
        return dependencies;
    }

    public Set<Object> getDependsOn() {
        return dependencies.getValues();
    }

    public void setDependsOn(Iterable<?> dependsOn) {
        nagIfTaskNotInConfigurableState("Task.setDependsOn(Iterable)");
        dependencies.setValues(dependsOn);
    }

    public void onlyIf(Closure onlyIfClosure) {
        nagIfTaskNotInConfigurableState("Task.onlyIf(Closure)");
        this.onlyIfSpec = this.onlyIfSpec.and(onlyIfClosure);
    }

    public void onlyIf(Spec<? super Task> onlyIfSpec) {
        nagIfTaskNotInConfigurableState("Task.onlyIf(Spec)");
        this.onlyIfSpec = this.onlyIfSpec.and(onlyIfSpec);
    }

    public void setOnlyIf(Spec<? super Task> spec) {
        nagIfTaskNotInConfigurableState("Task.setOnlyIf(Spec)");
        onlyIfSpec = createNewOnlyIfSpec().and(spec);
    }

    public void setOnlyIf(Closure onlyIfClosure) {
        nagIfTaskNotInConfigurableState("Task.setOnlyIf(Closure)");
        onlyIfSpec = createNewOnlyIfSpec().and(onlyIfClosure);
    }

    private AndSpec<Task> createNewOnlyIfSpec() {
        return new AndSpec<Task>(new Spec<Task>() {
            public boolean isSatisfiedBy(Task element) {
                return element == AbstractTask.this && enabled;
            }
        });
    }

    public Spec<? super TaskInternal> getOnlyIf() {
        return onlyIfSpec;
    }

    public boolean getDidWork() {
        return state.getDidWork();
    }

    public void setDidWork(boolean didWork) {
        state.setDidWork(didWork);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        nagIfTaskNotInConfigurableState("Task.setEnabled(boolean)");
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public Task deleteAllActions() {
        nagIfTaskNotInConfigurableState("Task.deleteAllActions()");
        actions.clear();
        return this;
    }

    public final void execute() {
        executeWithoutThrowingTaskFailure();
        state.rethrowFailure();
    }

    public void executeWithoutThrowingTaskFailure() {
        executer.execute(this, state);
    }

    public TaskExecuter getExecuter() {
        return executer;
    }

    public void setExecuter(TaskExecuter executer) {
        this.executer = executer;
    }

    public Task dependsOn(Object... paths) {
        nagIfTaskNotInConfigurableState("Task.dependsOn(Object...)");
        dependencies.add(paths);
        return this;
    }

    public Task doFirst(Action<? super Task> action) {
        nagIfTaskNotInConfigurableState("Task.doFirst(Action)");
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        actions.add(0, wrap(action));
        return this;
    }

    public Task doLast(Action<? super Task> action) {
        nagIfTaskNotInConfigurableState("Task.doLast(Action)");
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        actions.add(wrap(action));
        return this;
    }

    public int compareTo(Task otherTask) {
        int depthCompare = project.compareTo(otherTask.getProject());
        if (depthCompare == 0) {
            return getPath().compareTo(otherTask.getPath());
        } else {
            return depthCompare;
        }
    }

    public String toString() {
        return String.format("task '%s'", path);
    }

    public Logger getLogger() {
        return buildLogger;
    }

    public LoggingManager getLogging() {
        return loggingManager;
    }

    public StandardOutputCapture getStandardOutputCapture() {
        return loggingManager;
    }

    public Object property(String propertyName) throws MissingPropertyException {
        return extensibleDynamicObject.getProperty(propertyName);
    }

    public boolean hasProperty(String propertyName) {
        return extensibleDynamicObject.hasProperty(propertyName);
    }

    public void setProperty(String name, Object value) {
        extensibleDynamicObject.setProperty(name, value);
    }

    public Convention getConvention() {
        return extensibleDynamicObject.getConvention();
    }

    public ExtensionContainer getExtensions() {
        return getConvention();
    }

    public DynamicObject getAsDynamicObject() {
        return extensibleDynamicObject;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public TaskInputs getInputs() {
        if (!state.isConfigurable()) {
            return new WarningEmittedOnConfiguringTaskInputs(inputs, this);
        }
        return inputs;
    }

    public TaskOutputsInternal getOutputs() {
        if (!state.isConfigurable()) {
            return new WarningEmittedOnConfiguringTaskOutputsInternal(outputs, this);
        }
        return outputs;
    }

    protected ServiceRegistry getServices() {
        return services;
    }

    public boolean dependsOnTaskDidWork() {
        TaskDependency dependency = getTaskDependencies();
        for (Task depTask : dependency.getDependencies(this)) {
            if (depTask.getDidWork()) {
                return true;
            }
        }
        return false;
    }

    public Task doFirst(Closure action) {
        nagIfTaskNotInConfigurableState("Task.doFirst(Closure)");
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        actions.add(0, convertClosureToAction(action));
        return this;
    }

    public Task doLast(Closure action) {
        nagIfTaskNotInConfigurableState("Task.doLast(Closure)");
        if (action == null) {
            throw new InvalidUserDataException("Action must not be null!");
        }
        actions.add(convertClosureToAction(action));
        return this;
    }

    public Task leftShift(final Closure action) {
        nagIfTaskNotInConfigurableState("Task.leftShit(Closure)");
        nagUser = false;
        try {
            return doLast(action);
        } finally {
            nagUser = true;
        }

    }

    public Task configure(Closure closure) {
        return ConfigureUtil.configure(closure, this, false);
    }

    public File getTemporaryDir() {
        File dir = getServices().get(TemporaryFileProvider.class).newTemporaryFile(getName());
        dir.mkdirs();
        return dir;
    }

    // note: this method is on TaskInternal
    public Factory<File> getTemporaryDirFactory() {
        return new Factory<File>() {
            public File create() {
                return getTemporaryDir();
            }
        };
    }

    public void addValidator(TaskValidator validator) {
        validators.add(validator);
    }

    public List<TaskValidator> getValidators() {
        return validators;
    }

    private Action<Task> convertClosureToAction(Closure actionClosure) {
        return new ClosureTaskAction(actionClosure);
    }

    private Action<Task> wrap(final Action<? super Task> action) {
        return new TaskActionWrapper(action);
    }

    private static class TaskInfo {
        private final ProjectInternal project;
        private final String name;

        private TaskInfo(ProjectInternal project, String name) {
            this.name = name;
            this.project = project;
        }
    }

    private static class ClosureTaskAction implements Action<Task> {
        private final Closure closure;

        private ClosureTaskAction(Closure closure) {
            this.closure = closure;
        }

        public void execute(Task task) {
            closure.setDelegate(task);
            closure.setResolveStrategy(Closure.DELEGATE_FIRST);
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(closure.getClass().getClassLoader());
            try {
                if (closure.getMaximumNumberOfParameters() == 0) {
                    closure.call();
                } else {
                    closure.call(task);
                }
            } catch (InvokerInvocationException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw e;
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private static class TaskActionWrapper implements Action<Task> {
        private final Action<? super Task> action;

        public TaskActionWrapper(Action<? super Task> action) {
            this.action = action;
        }

        public void execute(Task task) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(action.getClass().getClassLoader());
            try {
                action.execute(task);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private class TaskStateAwareList implements List<Action<? super Task>> {
        private final List<Action<? super Task>> delegate;

        public TaskStateAwareList(List<Action<? super Task>> actions) {
            this.delegate = actions;
        }

        public boolean add(Action<? super Task> action) {
            nagIfTaskNotInConfigurableState("Task.getActions().add(Action)");
            return delegate.add(action);
        }

        public boolean remove(Object o) {
            nagIfTaskNotInConfigurableState("Task.getActions().remove(Object)");
            return delegate.remove(o);
        }

        public boolean containsAll(Collection<?> c) {
            return delegate.containsAll(c);
        }

        public boolean addAll(Collection<? extends Action<? super Task>> c) {
            nagIfTaskNotInConfigurableState("Task.getActions().addAll(Collection<? extends Action>");
            return delegate.addAll(c);
        }

        public boolean addAll(int index, Collection<? extends Action<? super Task>> c) {
            nagIfTaskNotInConfigurableState("Task.getActions().addAll(int, Collection<? extends Action>");
            return delegate.addAll(index, c);
        }

        public boolean removeAll(Collection<?> c) {
            nagIfTaskNotInConfigurableState("Task.getActions().removeAll(Collection)");
            return delegate.removeAll(c);
        }

        public boolean retainAll(Collection<?> c) {
            nagIfTaskNotInConfigurableState("Task.getActions().retainAll(Collection)");
            return delegate.retainAll(c);
        }

        public void clear() {
            nagIfTaskNotInConfigurableState("Task.getActions().clear()");
            delegate.clear();
        }

        public void add(int index, Action<? super Task> element) {
            nagIfTaskNotInConfigurableState("Task.getActions().add(int, Collection)");
            delegate.add(index, element);
        }

        public Action<? super Task> remove(int index) {
            nagIfTaskNotInConfigurableState("Task.getActions().remove(int)");
            return delegate.remove(index);
        }

        public int size() {
            return delegate.size();
        }

        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        public boolean contains(Object o) {
            return delegate.contains(o);
        }

        public Iterator<Action<? super Task>> iterator() {
            return delegate.iterator();
        }

        public Object[] toArray() {
            return delegate.toArray();
        }

        public <T> T[] toArray(T[] a) {
            return delegate.toArray(a);
        }

        @Override
        public boolean equals(Object o) {
            return delegate.equals(o);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        public Action<? super Task> get(int index) {
            return delegate.get(index);
        }

        public Action<? super Task> set(int index, Action<? super Task> element) {
            return delegate.set(index, element);
        }

        public int indexOf(Object o) {
            return delegate.indexOf(o);
        }

        public int lastIndexOf(Object o) {
            return delegate.lastIndexOf(o);
        }

        public ListIterator<Action<? super Task>> listIterator() {
            return delegate.listIterator();
        }

        public ListIterator<Action<? super Task>> listIterator(int index) {
            return delegate.listIterator(index);
        }

        public List<Action<? super Task>> subList(int fromIndex, int toIndex) {
            return delegate.subList(fromIndex, toIndex);
        }

    }

    private void nagIfTaskNotInConfigurableState(String method) {
        if (!AbstractTask.this.state.isConfigurable() && nagUser) {
                getLogger().warn(String.format("Calling %s after task execution has started has been deprecated and is scheduled to be removed in Gradle 2.0 Check the configuration of task task ':foo'. You may have misused '<<' at task declaration.", method, this));
        }
    }
}
