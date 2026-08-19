package io.scenariomesh.adapter.testng;

import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.ScenarioTask;
import io.scenariomesh.core.Ports.AdapterContext;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.core.Ports.ScenarioAdapter;
import io.scenariomesh.core.ScenarioIds;
import org.testng.*;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

public final class TestNgAdapter implements ScenarioAdapter {
    public static final String ID = "testng";
    public String id(){return ID;}
    public String framework(){return "testng";}
    public boolean isAvailable(ClassLoader cl){try{Class.forName("org.testng.TestNG",false,cl);return true;}catch(ClassNotFoundException e){return false;}}

    public List<ScenarioTask> discover(AdapterContext c)throws IOException{
        List<ScenarioTask> tasks=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(Path root:c.testRoots()){
            if(!Files.isDirectory(root))continue;
            try(Stream<Path> stream=Files.walk(root)){
                for(Path file:stream.filter(p->p.toString().endsWith(".class"))
                        .filter(p->!p.getFileName().toString().contains("$"))
                        .sorted().toList()){
                    String name=root.relativize(file).toString().replace('/','.').replace('\\','.').replaceAll("\\.class$","");
                    if(!c.discoverySelection().matchesClassName(name)) continue;
                    try{
                        Class<?> candidate=Class.forName(name,false,c.classLoader());
                        for(Method method:candidate.getDeclaredMethods()){
                            Test ann=method.getAnnotation(Test.class);
                            if(ann==null)continue;
                            String selector=name+"#"+method.toGenericString();
                            if(!seen.add(selector))continue;
                            tasks.add(new ScenarioTask(ScenarioIds.from(ID,selector),name+"."+method.getName(),ID,framework(),null,null,selector,Set.of(ann.groups()),Map.of("className",name,"methodName",method.getName())));
                        }
                    }catch(LinkageError|ClassNotFoundException|RuntimeException ignored){}
                }
            }
        }
        return List.copyOf(tasks);
    }

    public ExecutionResult execute(ScenarioTask task,ExecutionContext c)throws Exception{
        String className=task.metadata().get("className");
        String generic=task.selector().substring(task.selector().indexOf('#')+1);
        Class<?> clazz=Class.forName(className,false,c.classLoader());
        TestNG t=new TestNG(false);t.setUseDefaultListeners(false);t.setVerbose(0);t.setTestClasses(new Class<?>[]{clazz});
        String groups=System.getProperty("groups"),excluded=System.getProperty("excludedGroups");
        if(groups!=null&&!groups.isBlank())t.setGroups(groups);
        if(excluded!=null&&!excluded.isBlank())t.setExcludedGroups(excluded);
        t.setMethodInterceptor(new ExactMethodInterceptor(generic));
        CapturingListener listener=new CapturingListener();t.addListener(listener);
        Instant started=Instant.now();t.run();Instant finished=Instant.now();
        if(listener.executed==0)return new ExecutionResult(task.id(),task.displayName(),ResultStatus.INFRASTRUCTURE_FAILURE,Duration.between(started,finished),c.workerId(),c.attempt(),started,finished,"TestNG did not execute selected method "+task.displayName(),"SelectionFailure");
        if(listener.failure!=null)return new ExecutionResult(task.id(),task.displayName(),ResultStatus.TEST_FAILURE,Duration.between(started,finished),c.workerId(),c.attempt(),started,finished,listener.failure.getMessage(),listener.failure.getClass().getName());
        return new ExecutionResult(task.id(),task.displayName(),ResultStatus.PASSED,Duration.between(started,finished),c.workerId(),c.attempt(),started,finished,null,null);
    }

    private static final class ExactMethodInterceptor implements IMethodInterceptor{
        private final String generic;ExactMethodInterceptor(String generic){this.generic=generic;}
        public List<IMethodInstance> intercept(List<IMethodInstance> methods,ITestContext c){return methods.stream().filter(i->{Method m=i.getMethod().getConstructorOrMethod().getMethod();return m!=null&&m.toGenericString().equals(generic);}).toList();}
    }
    private static final class CapturingListener implements ITestListener{
        int executed;Throwable failure;
        public void onTestSuccess(ITestResult r){executed++;}
        public void onTestFailure(ITestResult r){executed++;failure=r.getThrowable();}
        public void onTestSkipped(ITestResult r){executed++;if(failure==null&&r.getThrowable()!=null)failure=r.getThrowable();}
    }
}
