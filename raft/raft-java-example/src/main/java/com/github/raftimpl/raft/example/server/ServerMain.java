package com.github.raftimpl.raft.example.server;

import com.baidu.brpc.server.RpcServer;
import com.baidu.brpc.server.RpcServerOptions;
import com.github.raftimpl.raft.RaftNode;
import com.github.raftimpl.raft.RaftOptions;
import com.github.raftimpl.raft.StateMachine;
import com.github.raftimpl.raft.example.server.machine.BTreeStateMachine;
import com.github.raftimpl.raft.example.server.machine.LevelDBStateMachine;
import com.github.raftimpl.raft.example.server.machine.RocksDBStateMachine;
import com.github.raftimpl.raft.example.server.service.ExampleService;
import com.github.raftimpl.raft.example.server.service.impl.ExampleServiceBucketImpl;
import com.github.raftimpl.raft.example.server.service.impl.ExampleServiceImpl;
import com.github.raftimpl.raft.proto.RaftProto;
import com.github.raftimpl.raft.service.RaftClientService;
import com.github.raftimpl.raft.service.RaftConsensusService;
import com.github.raftimpl.raft.service.impl.RaftClientServiceImpl;
import com.github.raftimpl.raft.service.impl.RaftConsensusServiceImpl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Created by raftimpl on 2017/5/9.
 */
@Configuration
@PropertySource(value = "classpath:my.properties")
//@ComponentScan(basePackages = "com.github.raftimpl.raft.example.server")
public class ServerMain {

    @Value("${machine.kind:leveldb}")
    private String machinekind;
    @Value("${bucketcapacity:20}")
    private String bucketcapacity;
    @Value("${bucketrate:10}")
    private String bucketrate;

    public static void main(String[] args) {
        try {
            ClassPathResource resource = new ClassPathResource("my.properties");
            if (!resource.exists()) {
                System.err.println("my.properties not found");
            } else {
                System.out.println("my.properties found");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ServerMain.class);
        ServerMain serverMain = context.getBean(ServerMain.class);
        serverMain.startServer(args);
    }

    public void startServer(String[] args) {
//        System.out.println(machinekind);
        if (args.length != 3) {
            System.out.printf("Usage: ./run_server.sh DATA_PATH CLUSTER CURRENT_NODE\n");
            System.exit(-1);
        }
        // parse args
        // raft data dir
        String dataPath = args[0];
        // peers, format is "host:port:serverId,host2:port2:serverId2"
        String servers = args[1];
        String[] splitArray = servers.split(",");
        List<RaftProto.Server> serverList = new ArrayList<>();
        for (String serverString : splitArray) {
            RaftProto.Server server = parseServer(serverString);
            serverList.add(server);
        }
        // local server
        RaftProto.Server localServer = parseServer(args[2]);

        // 初始化RPCServer
        RpcServerOptions options = new RpcServerOptions();
        options.setIoThreadNum(Runtime.getRuntime().availableProcessors() * 10);
        options.setWorkThreadNum(Runtime.getRuntime().availableProcessors() * 10);
        RpcServer server = new RpcServer(localServer.getEndpoint().getPort(), options);
        // 设置Raft选项，比如：
        // just for test snapshot
        RaftOptions raftOptions = new RaftOptions();
        raftOptions.setDataDir(dataPath);
        raftOptions.setSnapshotMinLogSize(10 * 1024);
        raftOptions.setSnapshotPeriodSeconds(30);
        raftOptions.setMaxSegmentFileSize(1024 * 1024);
        // 应用状态机
        StateMachine stateMachine;
        switch (machinekind) {
            case "btree":
                stateMachine = new BTreeStateMachine(raftOptions.getDataDir());
                break;
            case "leveldb":
                stateMachine = new LevelDBStateMachine(raftOptions.getDataDir());
                break;
            default:
                stateMachine = new RocksDBStateMachine(raftOptions.getDataDir());
                break;
        }

        // 初始化RaftNode
        RaftNode raftNode = new RaftNode(raftOptions, serverList, localServer, stateMachine);
        // 注册Raft节点之间相互调用的服务
        RaftConsensusService raftConsensusService = new RaftConsensusServiceImpl(raftNode);
        server.registerService(raftConsensusService);
        // 注册给Client调用的Raft服务
        RaftClientService raftClientService = new RaftClientServiceImpl(raftNode);
        server.registerService(raftClientService);
        // 注册应用自己提供的服务
//        ExampleService exampleService = new ExampleServiceImpl(raftNode, stateMachine);
//        server.registerService(exampleService);
        ExampleService exampleService = new ExampleServiceBucketImpl(raftNode, stateMachine,Integer.parseInt(bucketcapacity),Integer.parseInt(bucketrate));
        server.registerService(exampleService);
        // 启动RPCServer，初始化Raft节点
        server.start();
        raftNode.init();
    }

    private static RaftProto.Server parseServer(String serverString) {
        String[] splitServer = serverString.split(":");
        String host = splitServer[0];
        Integer port = Integer.parseInt(splitServer[1]);
        Integer serverId = Integer.parseInt(splitServer[2]);
        RaftProto.Endpoint endPoint = RaftProto.Endpoint.newBuilder()
                .setHost(host).setPort(port).build();
        RaftProto.Server.Builder serverBuilder = RaftProto.Server.newBuilder();
        RaftProto.Server server = serverBuilder.setServerId(serverId).setEndpoint(endPoint).build();
        return server;
    }
}
