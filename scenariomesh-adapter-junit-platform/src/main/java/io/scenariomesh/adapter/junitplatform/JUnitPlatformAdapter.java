package io.scenariomesh.adapter.junitplatform;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.PostDiscoveryFilter;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;

public final class JUnitPlatformAdapter implements ScenarioAdapter {
    public static final String ID = "junit-platform";
    public String id(){return ID;}
    public String framework(){return "junit-platform";}

    public boolean isAvailable(ClassLoader cl){
        try{
            Class.forName("org.junit.platform.launcher.Launcher",false,cl);
            return ServiceLoader.load(TestEngine.class,cl).iterator().hasNext();
        }catch(ClassNotFoundException e){return false;}
    }

    public List<ScenarioTask> discover(AdapterContext c){
        if(c.testRoots().isEmpty())return List.of();
        LauncherDiscoveryRequestBuilder builder=LauncherDiscoveryRequestBuilder.request()
                .selectors(selectClasspathRoots(new HashSet<>(c.testRoots())))
                .filters(EngineFilter.excludeEngines("junit-vintage"));
        if(!c.discoverySelection().includeClassNameRegexes().isEmpty()){
            builder.filters(ClassNameFilter.includeClassNamePatterns(
                    c.discoverySelection().includeClassNameRegexes().toArray(String[]::new)));
        }
        if(!c.discoverySelection().excludeClassNameRegexes().isEmpty()){
            builder.filters(ClassNameFilter.excludeClassNamePatterns(
                    c.discoverySelection().excludeClassNameRegexes().toArray(String[]::new)));
        }
        LauncherDiscoveryRequest req=builder.build();
        Launcher launcher=LauncherFactory.create();
        TestPlan plan=launcher.discover(req);
        List<ScenarioTask> tasks=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(TestIdentifier root:plan.getRoots()){
            for(TestIdentifier id:plan.getDescendants(root)){
                if(!id.isTest()||!plan.getChildren(id).isEmpty())continue;
                String uid=id.getUniqueId();if(!seen.add(uid))continue;
                Set<String> tags=new HashSet<>();for(TestTag tag:id.getTags())tags.add(tag.getName());
                String fw=uid.contains("[engine:cucumber]")?"cucumber-junit-platform":"junit5";
                tasks.add(new ScenarioTask(ScenarioIds.from(ID,uid),id.getDisplayName(),ID,fw,null,null,uid,tags,Map.of("uniqueId",uid)));
            }
        }
        return List.copyOf(tasks);
    }

    public ExecutionResult execute(ScenarioTask task,ExecutionContext c){
        Instant started=Instant.now();SummaryGeneratingListener listener=new SummaryGeneratingListener();
        LauncherDiscoveryRequest req=LauncherDiscoveryRequestBuilder.request().selectors(selectUniqueId(task.selector())).filters(EngineFilter.excludeEngines("junit-vintage")).build();
        Launcher launcher=LauncherFactory.create();launcher.registerTestExecutionListeners(listener);launcher.execute(req);
        var summary=listener.getSummary();Instant finished=Instant.now();
        if(summary.getTestsFoundCount()==0)return new ExecutionResult(task.id(),task.displayName(),ResultStatus.INFRASTRUCTURE_FAILURE,Duration.between(started,finished),c.workerId(),c.attempt(),started,finished,"JUnit Platform did not execute the selected test: "+task.selector(),"SelectionFailure");
        if(summary.getTestsFailedCount()>0){Throwable failure=summary.getFailures().isEmpty()?null:summary.getFailures().get(0).getException();return new ExecutionResult(task.id(),task.displayName(),ResultStatus.TEST_FAILURE,Duration.between(started,finished),c.workerId(),c.attempt(),started,finished,failure==null?"Test failed":failure.getMessage(),failure==null?null:failure.getClass().getName());}
        return new ExecutionResult(task.id(),task.displayName(),ResultStatus.PASSED,Duration.between(started,finished),c.workerId(),c.attempt(),started,finished,null,null);
    }
}
