package WebSocketTest;


import com.alibaba.fastjson.JSONObject;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.ext.web.Router;

import org.javatuples.Triplet;


import java.util.HashMap;
import java.util.Map;

//WebSocket 服务为玩家消息代理，向其他服务者发出和接收消息
public class WebSocketVerticle extends AbstractVerticle {
    // 保存每一个连接到服务器的通道
    //用户Id，用户信息
    private Map<String, Triplet<String, String, ServerWebSocket>> connectionMap = new HashMap<>(16);

    /*
    元组为：玩家位置，玩家状态，玩家webSocket
    位置：loginHall 去大厅
            inHall  在大厅
            goRoom  正在去房间
            Room + 数字  在房间，数字代表房间号
     状态：free 未在游戏中
            play 在游戏中
            号码 正在进入房间X，但是还未正式进入
     * */
    private boolean checkID(String aid) {
        return connectionMap.containsKey(aid);
    }

    @Override
    public void start() {
        HttpServer server = vertx.createHttpServer();
        Router router = Router.router(vertx);
        router.route("/").handler(routingContext -> {
            routingContext.response().sendFile("html/ws.html");
        });
        webSocketMethod(server);
        server.requestHandler(router).listen(8080);
        DeploymentOptions options = new DeploymentOptions().setWorker(true);
        vertx.deployVerticle(HallVerticle.class.getName(), options);
    }

    private void loginHallProcess(String someId, ServerWebSocket someWebSocket, EventBus eb) {
        connectionMap.put(someId, new Triplet<>("loginHall", "free", someWebSocket));

        System.out.println("ws：发送进入大厅请求：" + someId);
        eb.send("player.inHall", someId, ar -> {
                    if (ar.succeeded()) {
                        String who = ar.result().body().toString();
                        System.out.println("ws:收到进入大厅回复：" + who);
                        if (who.equals(someId) && connectionMap.get(who).getValue0().equals("loginHall")) {

                            connectionMap.put(someId, new Triplet<>("inHall", "free", someWebSocket));
                            System.out.println("ws：进入大厅成功" + who);

                        } else {
                            connectionMap.remove(who);
                            System.out.println("ws：没有此玩家或玩家状态错误：" + who);
                            someWebSocket.close();

                        }

                    } else {
                        System.out.println("进入大厅消息未回复，大厅处理有问题");
                    }
                }
        );
    }

    private void webSocketMethod(HttpServer server) {
        server.websocketHandler(webSocket -> { // 获取每一个链接的ID
            String id = webSocket.binaryHandlerID();
            //TODO Login Server
            EventBus eb = vertx.eventBus();
            // 判断当前连接的ID是否存在于map集合中，如果不存在则添加进map集合中
            if (!checkID(id)) {
                //TODO reconnect
                loginHallProcess(id, webSocket, eb);
            } else {
                //TODO ???
            }
            System.out.print("ws：id列表");
            for (Map.Entry<String, Triplet<String, String, ServerWebSocket>> entry : connectionMap.entrySet()) {
                System.out.println(entry.getKey());
            }


            //OFFLineResp
            webSocket.closeHandler(handler -> {
                        String playerPosition = connectionMap.get(id).getValue0();
                        JSONObject whoAndReason = new JSONObject();
                        whoAndReason.put("id", id);
                        whoAndReason.put("reason", "offLine");

                        String whoAndReasonMsg = whoAndReason.toJSONString();
                        if (playerPosition.startsWith("Room")) {
                            eb.send("quit" + playerPosition, whoAndReasonMsg);
                        } else
                            switch (connectionMap.get(id).getValue0()) {
                                case "loginHall":
                                    eb.send("cancelLogin", whoAndReasonMsg);
                                    break;
                                //如果状态已在大厅，直接发送id
                                case "inHall":
                                    eb.send("quitHall", id);
                                    break;
                                case "creatingRoom":
                                    eb.send("cancelCreate", whoAndReasonMsg);
                                    break;

                                case "findingRoom":
                                    //向大厅发送取消寻找，如果不是早于大厅的寻找流程，那么就会向房间发送退出消息
                                    eb.send("cancelFind", whoAndReasonMsg,
                                            messageAsyncResult -> {
                                                if (messageAsyncResult.succeeded())
                                                    if (!messageAsyncResult.result().body().equals("early")) {
                                                        String roomId = messageAsyncResult.result().body().toString();
                                                        eb.send("quitRoom" + roomId, whoAndReasonMsg);
                                                    }
                                            }

                                    );
                                    break;
                                //在正在加入状态，说明在状态上保存了房间号，但是没有进入，通知该房间谁退出
                                case "joiningRoom":
                                    String RoomId = connectionMap.get(id).getValue1();
                                    eb.send("quitRoom" + RoomId, whoAndReasonMsg);
                                    break;

                            }


                        connectionMap.remove(id);
                    }
            );


            //　WebSocket 连接
            webSocket.frameHandler(handler -> {
                String textData = handler.textData();
                String currID = webSocket.binaryHandlerID();//TODO webSocket to find Id
                //TODO proto decode

                //创建 加入房间只能位于大厅操作
                switch (textData) {
                    case "createRoom": {
                        if (connectionMap.get(currID).getValue0().equals("inHall")) {
                            {
                                connectionMap.put(currID, new Triplet<>("creatingRoom", "free", webSocket));
                                eb.send("createRoom", currID, ar ->

                                {
                                    JSONObject crInfo =
                                            JSONObject.parseObject(ar.result().body().toString());

                                    String cid = crInfo.getString("id");
                                    int RoomId = crInfo.getInteger("room");
                                    if (connectionMap.get(cid).getValue0().equals("creatingRoom") && cid.equals(id)) {
                                        connectionMap.put(cid, new Triplet<>("Room" + RoomId, "free", webSocket));
                                        System.out.println("回复房间:" + RoomId + "开启ok" + cid);
                                        connectionMap.get(cid).getValue2().writeTextMessage("房间建立成功，房间号：" + RoomId);

                                    } else {
                                        System.out.println("ws：createRoom：没有此玩家或玩家状态错误：" + cid);
                                    }
                                });
                            }
                        } else {
                            connectionMap.get(currID).getValue2().writeTextMessage("不可建立房间，你不在大厅中");
                        }
                        break;
                    }
                    case "joinRoom": {
                        if (connectionMap.get(currID).getValue0().equals("inHall")) {
                            connectionMap.put(currID, new Triplet<>("findingRoom", "free", webSocket));
                            //向大厅请求一个有位置的房间号
                            eb.send("findRoom", currID, messageAsyncResult -> {
                                if (messageAsyncResult.succeeded() && !messageAsyncResult.result().body().equals("fail")) {
                                    String roomId = messageAsyncResult.result().body().toString();
                                    //请求到房间成功后，开始进入房间
                                    connectionMap.put(currID, new Triplet<>("joiningRoom", roomId, webSocket));
                                    eb.send("joinRoom" + roomId, currID, messageAsyncResult1 -> {
                                        //房间回复ok，则记录在房间的状态
                                        if (messageAsyncResult1.succeeded() && messageAsyncResult1.result().body().equals("ok")) {
                                            connectionMap.get(currID).getValue2().writeTextMessage("房间进入成功，你在房间" + roomId);
                                            connectionMap.put(currID, new Triplet<>("Room" + roomId, "free", webSocket));
                                        } else {
                                            connectionMap.get(currID).getValue2().writeTextMessage("房间出问题，不可进入");
                                            connectionMap.put(currID, new Triplet<>("inHall", "free", webSocket));
                                        }
                                    });

                                } else {
                                    connectionMap.get(currID).getValue2().writeTextMessage("没有剩余的空房间");
                                    connectionMap.put(currID, new Triplet<>("inHall", "free", webSocket));
                                }
                            });
                        } else {
                            connectionMap.get(currID).getValue2().writeTextMessage("不可加入房间，你不在大厅中");
                        }
                        break;
                    }
                    case "leaveRoom": {
                        if (connectionMap.get(currID).getValue0().startsWith("Room")) {
                            JSONObject whoAndReason = new JSONObject();
                            whoAndReason.put("id", currID);
                            whoAndReason.put("reason", "normal");
                            String whoAndReasonMsg = whoAndReason.toJSONString();
                            eb.send("quit" + connectionMap.get(currID).getValue0(), whoAndReasonMsg);
                            loginHallProcess(currID, webSocket, eb);
                            connectionMap.get(currID).getValue2().writeTextMessage("退出成功，你回到大厅");
                        } else {
                            connectionMap.get(currID).getValue2().writeTextMessage("不可退出房间，你不在房间中");
                        }
                    }


                    default:
                        //给非当前连接到服务器的每一个WebSocket连接发送消息
                        for (Map.Entry<String, Triplet<String, String, ServerWebSocket>> entry : connectionMap.entrySet()) {

                            if (currID.equals(entry.getKey())) {
                                continue;
                            }

                    /* 发送文本消息 文本信息似乎不支持图片等二进制消息
                    若要发送二进制消息，可用writeBinaryMessage方法
                    */
//
//                            JSONObject msg = new JSONObject();
//                            msg.put("name", "abc");
//                            msg.put("age", 11);
//                            String output = msg.toJSONString();
//                            System.out.println(output);
//                            eb.send("test.address", output);
                            entry.getValue().getValue2().writeTextMessage("用户" + id + ":" + textData + "\r");
                        }
                        break;
                }
            });
        });
    }
}

