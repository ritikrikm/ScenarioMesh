package io.scenariomesh.workerruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.scenariomesh.core.Domain.ExecutionResult;
import io.scenariomesh.core.Domain.ResultStatus;
import io.scenariomesh.core.Domain.WorkerId;
import io.scenariomesh.core.Ports.ExecutionContext;
import io.scenariomesh.protocol.Protocol;
import io.scenariomesh.protocol.Protocol.Envelope;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class WorkerMain {
    private WorkerMain() {}
    public static void main(String[] args) throws Exception {
        Arguments parsed=Arguments.parse(args);ObjectMapper mapper=JsonCodec.create();AdapterRegistry adapters=new AdapterRegistry();ClassLoader classLoader=Thread.currentThread().getContextClassLoader();Map<String,String> properties=new HashMap<>();System.getProperties().forEach((key,value)->properties.put(String.valueOf(key),String.valueOf(value)));
        try(Socket socket=new Socket(InetAddress.getByName(parsed.host),parsed.port);BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8))){
            write(mapper,writer,Envelope.hello(parsed.workerId,parsed.token));
            for(String line;(line=reader.readLine())!=null;){Envelope envelope=mapper.readValue(line,Envelope.class);validate(envelope);if(envelope.type()==Protocol.Type.STOP){write(mapper,writer,Envelope.ack(parsed.workerId));return;}if(envelope.type()!=Protocol.Type.RUN||envelope.task()==null){write(mapper,writer,Envelope.error(parsed.workerId,"Expected RUN command"));continue;}ExecutionResult result;try{result=adapters.required(envelope.task().adapterId()).execute(envelope.task(),new ExecutionContext(classLoader,new WorkerId(parsed.workerId),1,properties));}catch(Exception exception){Instant now=Instant.now();result=new ExecutionResult(envelope.task().id(),envelope.task().displayName(),ResultStatus.INFRASTRUCTURE_FAILURE,Duration.ZERO,new WorkerId(parsed.workerId),1,now,now,exception.getMessage(),exception.getClass().getName());}write(mapper,writer,Envelope.result(parsed.workerId,result));}
        }
    }
    private static void validate(Envelope envelope){if(envelope.protocolVersion()!=Protocol.VERSION)throw new IllegalArgumentException("Unsupported ScenarioMesh protocol version: "+envelope.protocolVersion());}
    private static void write(ObjectMapper mapper,BufferedWriter writer,Envelope envelope)throws Exception{writer.write(mapper.writeValueAsString(envelope));writer.newLine();writer.flush();}
    private record Arguments(String host,int port,String token,String workerId){private static Arguments parse(String[] args){String host=null,token=null,workerId=null;Integer port=null;for(int i=0;i<args.length;i++){String key=args[i];if(i+1>=args.length)throw new IllegalArgumentException(key+" requires a value");String value=args[++i];switch(key){case "--host"->host=value;case "--port"->port=Integer.parseInt(value);case "--token"->token=value;case "--worker-id"->workerId=value;default->throw new IllegalArgumentException("Unknown worker argument: "+key);}}if(host==null||port==null||token==null||workerId==null)throw new IllegalArgumentException("--host, --port, --token and --worker-id are required");return new Arguments(host,port,token,workerId);}}
}
