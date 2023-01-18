##1.概述
jraft中的rheakv可用于分布式存储kv。下面就一条简单的put(k,v)命令记录一下存储的整个流程。
说明：为了方便，这里约定，下面提到的分布式集群中有3个节点分别为A，B，C，三个节点，A为主节点，B，C为从节点

##2.1客户端
首先客户端执行一条put(k,v)命令的分析。客户端执行这样一条简单命令背后做的工作可以分解为一下几个步骤
* 客户端首先获取集群的主节点(leader)的ip和端口
* 获取到ip和端口后，将和leader建立起连接。
* 建立连接后，就可以向leader发送rpc请求，请求内容就是put命令，以及对应的k,v 数据。rpc请求底层使用netty发送和接受。


##2.2 服务端

Leader收到之后，会生成一个日志对象（这里是一个简单的put操作，如果是多个put操作，或者是batchput多个kv，
这里就会生成多个日志对象，每个日志对象对应一个put操作）。Leader在自己节点会执行这个put操作，与此同时也会
向follow发起请求，请求客户端执行相同的put操作。follow执行后会给leader一个response。主节点会统计成功
执行put操作的节点个数。如果有超过一半的节点成功执行了put操作，那就代表当前集群应用了put操作。  
例如一个5节点的集群，主节点自身的put肯定是成功的，然后只要从4个从节点中收到2个成功的response（成功的
response代表了从节点成功执行了put操作），就超过了集群总节点的一半。

##2.3 代码流程
因为客户端的请求比较简单，这里暂时不做分析，只分析服务端收到客户端请求后的代码执行逻辑

###2.3.1 服务端收到rpc请求
上面提到，客户端请求是通过netty发送和接受的。在服务端，rpc请求的接受在底层也是netty完成的。框架本身自定义了一
个netty的handler  也就是RpcHandler。客户端的请求会经过这个RpcHandler。我们就从这里开始分析。
下面是RpcHandler的channelRead方法，请求会首先经过这里。方法的第二个入参msg包含了客户端提交的具体操作（比如是put
或者update或者其他一些操作）以及操作相关的数据。

```java
//msg包含了客户端提交的具体操作（比如是put或者update或者其他一些操作）以及操作相关的数据。
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ProtocolCode protocolCode = ctx.channel().attr(Connection.PROTOCOL).get();
        Protocol protocol = ProtocolManager.getProtocol(protocolCode);
        protocol.getCommandHandler().handleCommand(
            new RemotingContext(ctx, new InvokeContext(), serverSide, userProcessors), msg);
}
```

在channelRead(ChannelHandlerContext ctx, Object msg)方法中，又交给一个CommandHandler对象来处理（
CommandHandler接口只有一个实现类，就是RpcCommandHandler），进入handleCommand方法，并往下走会来到
RpcCommandHandler.handle(final RemotingContext ctx, final Object msg)方法。

```java
    private void handle(final RemotingContext ctx, final Object msg) {
        try {
            //如果只是一个简单的put操作，这里不是List。如果是batch操作，比如说客户端使用
            // batchPut命令同时存入多个kv，那么这里就会是list
            if (msg instanceof List) {
                final Runnable handleTask = new Runnable() {
                    @Override
                    public void run() {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Batch message! size={}", ((List<?>) msg).size());
                        }
                        for (final Object m : (List<?>) msg) {
                            RpcCommandHandler.this.process(ctx, m);
                        }
                    }
                };
                if (RpcConfigManager.dispatch_msg_list_in_default_executor()) {
                    // If msg is list ,then the batch submission to biz threadpool can save io thread.
                    // See com.alipay.remoting.decoder.ProtocolDecoder
                    processorManager.getDefaultExecutor().execute(handleTask);
                } else {
                    handleTask.run();
                }
            } else {
                //如果是简单的的操作，比如一个put操作，会走这里
                process(ctx, msg);
            }
        } catch (final Throwable t) {
            processException(ctx, msg, t);
        }
    }
```

process方法如下

```java
    private void process(RemotingContext ctx, Object msg) {
        try {
            final RpcCommand cmd = (RpcCommand) msg;
            final RemotingProcessor processor = processorManager.getProcessor(cmd.getCmdCode());
            processor.process(ctx, cmd, processorManager.getDefaultExecutor());
        } catch (final Throwable t) {
            processException(ctx, msg, t);
        }
    }
```

在process方法中获取一个processor对象，这里的processor对象是RpcRequestProcessor，
全限定名是com.alipay.remoting.rpc.protocol.RpcRequestProcessor（框架中还有一个
同样的类名，注意区分）。


RpcRequestProcessor.process方法如下
```java
    public void process(RemotingContext ctx, RpcRequestCommand cmd, ExecutorService defaultExecutor)
                                                                                                    throws Exception {
        if (!deserializeRequestCommand(ctx, cmd, RpcDeserializeLevel.DESERIALIZE_CLAZZ)) {
            return;
        }
        UserProcessor userProcessor = ctx.getUserProcessor(cmd.getRequestClass());
        if (userProcessor == null) {
            String errMsg = "No user processor found for request: " + cmd.getRequestClass();
            logger.error(errMsg);
            sendResponseIfNecessary(ctx, cmd.getType(), this.getCommandFactory()
                .createExceptionResponse(cmd.getId(), errMsg));
            return;// must end process
        }

        // set timeout check state from user's processor
        ctx.setTimeoutDiscard(userProcessor.timeoutDiscard());

        // to check whether to process in io thread
        if (userProcessor.processInIOThread()) {
            if (!deserializeRequestCommand(ctx, cmd, RpcDeserializeLevel.DESERIALIZE_ALL)) {
                return;
            }
            // process in io thread
            new ProcessTask(ctx, cmd).run();
            return;// end
        }

        Executor executor;
        // to check whether get executor using executor selector
        if (null == userProcessor.getExecutorSelector()) {
            executor = userProcessor.getExecutor();
        } else {
            // in case haven't deserialized in io thread
            // it need to deserialize clazz and header before using executor dispath strategy
            if (!deserializeRequestCommand(ctx, cmd, RpcDeserializeLevel.DESERIALIZE_HEADER)) {
                return;
            }
            //try get executor with strategy
            executor = userProcessor.getExecutorSelector().select(cmd.getRequestClass(),
                cmd.getRequestHeader());
        }

        // Till now, if executor still null, then try default
        if (executor == null) {
            executor = (this.getExecutor() == null ? defaultExecutor : this.getExecutor());
        }

        // use the final executor dispatch process task
        //这一行就是核心代码，其实就是将请求交给线程池进行处理。也是就是说当前线程只是简
        // 单的接受一下客户端的请求，不做过多的处理。对请求的具体处理是交个线程池执行了。
        // 这里的ProcessTask 是Runnable的实现类，具体的处理逻辑就在run方法中。
        executor.execute(new ProcessTask(ctx, cmd));
    }
```

####2.3.2 第一个线程池

上面提到请求是交给了线程池在后台处理。具体的处理逻辑是在ProcessTask（ProcessTask是
AbstractRemotingProcessor的一个内部类，不是私有内部类，子类可以继承）的run方法中。方法核心代码如下,
run方法又调用了AbstractRemotingProcessor.doProcess方法。

```java
    AbstractRemotingProcessor.this.doProcess(ctx, msg);
```

上面一行代码中AbstractRemotingProcessor.this指的01就是创建ProcessTask对象时所在的对象。从2.3.1结尾
处可以知道AbstractRemotingProcessor.this就是RpcRequestProcessor对象。那么这里就清楚了，是
RpcRequestProcessor的process方法创建了一个任务，然后交给线程池执行，这个任务的内容其实就是当前
RpcRequestProcessor对象的doProcess方法。方法内容如下

```java
    @Override
    public void doProcess(final RemotingContext ctx, RpcRequestCommand cmd) throws Exception {
        long currentTimestamp = System.currentTimeMillis();

        preProcessRemotingContext(ctx, cmd, currentTimestamp);
        if (ctx.isTimeoutDiscard() && ctx.isRequestTimeout()) {
            timeoutLog(cmd, currentTimestamp, ctx);// do some log
            return;// then, discard this request
        }
        debugLog(ctx, cmd, currentTimestamp);
        // decode request all
        if (!deserializeRequestCommand(ctx, cmd, RpcDeserializeLevel.DESERIALIZE_ALL)) {
            return;
        }
        //将请求内容分发到用户自定义的Processor
        dispatchToUserProcessor(ctx, cmd);
    }
```

dispatchToUserProcessor方法比较长，核心代码如下
```java
//从context中获取提前注册好的UserProcessor
UserProcessor processor = ctx.getUserProcessor(cmd.getRequestClass());
...
//这里注意一下方法handleRequest最后一个参数cmd.getRequestObject()，之前我们说过客户端
// 请求内容在cmd中，实际上是在cmd的RequestObject中。第一个和第二个参数可以理解为对ctx
// 这个context的封装，然后可以加上自己的逻辑，有点类似代理。实际上在jraft内有很多这种封装
// 或者说代理，而且会是层层封装。就比如这里将ctx封装成一个”更高级“的context。这个”更高级“
// 的context，后面又会被层层封装。

processor.handleRequest(
        processor.preHandleRequest(ctx, cmd.getRequestObject()),
        new RpcAsyncContext(ctx, cmd, this), cmd.getRequestObject()
);
```
上面代码用的UserProcessor是提前注册好的，是一个匿名内部类，在BoltRpcServer中。

```java
    @Override
    public void registerProcessor(final RpcProcessor processor) {
        
        //注册UserProcessor
        this.rpcServer.registerUserProcessor(new AsyncUserProcessor<Object>() {

            @SuppressWarnings("unchecked")
            @Override
            //这里的第一个和第二个参数，内部就是封装了上一步提到的ctx
            public void handleRequest(final BizContext bizCtx, final AsyncContext asyncCtx, final Object request) {
                //这里对上一步封装产生的context，再次封装
                final RpcContext rpcCtx = new RpcContext() {

                    @Override
                    public void sendResponse(final Object responseObj) {
                        asyncCtx.sendResponse(responseObj);
                    }

                    @Override
                    public Connection getConnection() {
                        com.alipay.remoting.Connection conn = bizCtx.getConnection();
                        if (conn == null) {
                            return null;
                        }
                        return new BoltConnection(conn);
                    }

                    @Override
                    public String getRemoteAddress() {
                        return bizCtx.getRemoteAddress();
                    }
                };
                
                //使用传入的processor处理请求
                processor.handleRequest(rpcCtx, request);
            }

            @Override
            public String interest() {
                return processor.interest();
            }

            @Override
            public ExecutorSelector getExecutorSelector() {
                final RpcProcessor.ExecutorSelector realSelector = processor.executorSelector();
                if (realSelector == null) {
                    return null;
                }
                return realSelector::select;
            }

            @Override
            public Executor getExecutor() {
                return processor.executor();
            }
        });
    }

```

上述代码中的processor是KVCommandProcessor。KVCommandProcessor.handleRequest方法部分内容如下
```java
@Override
    public void handleRequest(final RpcContext rpcCtx, final T request) {
        Requires.requireNonNull(request, "request");
        //这里是对上一步封装后的context进一步封装，只不过这里是封装成了Closure回调，
        // 其实封装成什么，作用都和代理类似，都是为了加入自己的逻辑。这里的closure后
        // 面还会继续封装成“更高级”的closure
        final RequestProcessClosure<BaseRequest, BaseResponse<?>> closure = new RequestProcessClosure<>(request, rpcCtx);
        final RegionKVService regionKVService = this.storeEngine.getRegionKVService(request.getRegionId());
        if (regionKVService == null) {
            final NoRegionFoundResponse noRegion = new NoRegionFoundResponse();
            noRegion.setRegionId(request.getRegionId());
            noRegion.setError(Errors.NO_REGION_FOUND);
            noRegion.setValue(false);
            closure.sendResponse(noRegion);
            return;
        }
        //根据request中的操作标识符判断是哪种操作
        switch (request.magic()) {
            case BaseRequest.PUT:
                regionKVService.handlePutRequest((PutRequest) request, closure);
                break;
            case BaseRequest.BATCH_PUT:
                regionKVService.handleBatchPutRequest((BatchPutRequest) request, closure);
                break;
        ...
        }
    }
```

jraft提供了很多操作命令，我们这里就看最简单的put操作。
```java
    public static final byte  PUT              = 0x01;
    public static final byte  BATCH_PUT        = 0x02;
    public static final byte  PUT_IF_ABSENT    = 0x03;
    public static final byte  GET_PUT          = 0x04;
    public static final byte  DELETE           = 0x05;
    public static final byte  DELETE_RANGE     = 0x06;
    public static final byte  MERGE            = 0x07;
    public static final byte  GET              = 0x08;
    public static final byte  MULTI_GET        = 0x09;
    public static final byte  SCAN             = 0x0a;
    public static final byte  GET_SEQUENCE     = 0x0b;
    public static final byte  RESET_SEQUENCE   = 0x0c;
    public static final byte  KEY_LOCK         = 0x0d;
    public static final byte  KEY_UNLOCK       = 0x0e;
    public static final byte  NODE_EXECUTE     = 0x0f;
    public static final byte  RANGE_SPLIT      = 0x10;
    public static final byte  COMPARE_PUT      = 0x11;
    public static final byte  BATCH_DELETE     = 0x12;
    public static final byte  CONTAINS_KEY     = 0x13;

```

继续往下走，来到DefaultRegionKVService.handlePutRequest方法
```java
    public void handlePutRequest(final PutRequest request,
                                 final RequestProcessClosure<BaseRequest, BaseResponse<?>> closure) {
        final PutResponse response = new PutResponse();
        response.setRegionId(getRegionId());
        response.setRegionEpoch(getRegionEpoch());
        try {
            KVParameterRequires.requireSameEpoch(request, getRegionEpoch());
            final byte[] key = KVParameterRequires.requireNonNull(request.getKey(), "put.key");
            final byte[] value = KVParameterRequires.requireNonNull(request.getValue(), "put.value");

            //注意下这个put方法的第三个参数，是一个匿名内部类。其实就是对上一步的closure
            // 进一步封装。这里的BaseKVStoreClosure到后面又会层层封装成一个新的closure。
            // 当客户端提交到集群中的操作执行结束之后，会回调最上层的closure，然后层层调用来到这里的run方法。
            // 并且传入一个状态，这个状态就是集群执行执行的结果是成功还是失败。
            this.rawKVStore.put(key, value, new BaseKVStoreClosure() {

                @Override
                //根据status成功与否，响应客户端不同的内容。
                public void run(final Status status) {
                    if (status.isOk()) {
                        response.setValue((Boolean) getData());
                    } else {
                        setFailure(request, response, status, getError());
                    }
                    //这个closure就是上一步封装context产生的对象。而context又是层层封装。
                    // “最底层”的context其实是关联了netty中的一个channle。而这个channel
                    // 又关联了客户端。这行代码会调用下一层的context，然后层层调用，就到了
                    // channel，最终通过channel将响应发送到客户端。
                    closure.sendResponse(response);
                }
            });
        } catch (final Throwable t) {
            LOG.error("Failed to handle: {}, {}.", request, StackTraceUtil.stackTrace(t));
            response.setError(Errors.forException(t));
            closure.sendResponse(response);
        }
    }

```
this.rawKVStore.put是MetricsRawKVStore.put(final byte[] key, final byte[] value, final KVStoreClosure closure)，
然后又调用RaftRawKVStore.put(final byte[] key, final byte[] value, final KVStoreClosure closure)，
接着是RaftRawKVStore.applyOperation方法

```java
private void applyOperation(final KVOperation op, final KVStoreClosure closure) {
        if (!isLeader()) {
            closure.setError(Errors.NOT_LEADER);
            closure.run(new Status(RaftError.EPERM, "Not leader"));
            return;
        }
        final Task task = new Task();
        task.setData(ByteBuffer.wrap(Serializers.getDefault().writeObject(op)));
        //把closure又封装了一次
        task.setDone(new KVClosureAdapter(closure, op));
        //task对象包含了操作命令，操作数据kv，以及回调closure，task就好比是个载体，
        // 只不过这个载体是个短命鬼，活不过2集
        this.node.apply(task);
}
```


然后是 NodeImpl.apply方法

```java
    public void apply(final Task task) {
        if (this.shutdownLatch != null) {
            Utils.runClosureInThread(task.getDone(), new Status(RaftError.ENODESHUTDOWN, "Node is shutting down."));
            throw new IllegalStateException("Node is shutting down");
        }
        Requires.requireNonNull(task, "Null task");
    
        //LogEntry是日志对象。
        final LogEntry entry = new LogEntry();
        entry.setData(task.getData());
        int retryTimes = 0;
        try {

            //这个translator可以简单的看做一个转换器，将task装载的信息转移到event上。
            // 所以这里的event也可以看做一种载体。最终这个装载了信息的event会被放入一个
            // 队列。然后有一个线程池会周而复始的从队列取出event，并交给eventHandler处理。
            final EventTranslator<LogEntryAndClosure> translator = (event, sequence) -> {
                event.reset();
                event.done = task.getDone();
                event.entry = entry;
                event.expectedTerm = task.getExpectedTerm();
            };
            while (true) {
                //这个tryPublishEvent方法就是使用转换器，将信息转移到event，并将
                // event放入一个队列
                if (this.applyQueue.tryPublishEvent(translator)) {
                    break;
                } else {
                    retryTimes++;
                    if (retryTimes > MAX_APPLY_RETRY_TIMES) {
                        Utils.runClosureInThread(task.getDone(),
                            new Status(RaftError.EBUSY, "Node is busy, has too many tasks."));
                        LOG.warn("Node {} applyQueue is overload.", getNodeId());
                        this.metrics.recordTimes("apply-task-overload-times", 1);
                        return;
                    }
                    ThreadHelper.onSpinWait();
                }
            }

        } catch (final Exception e) {
            LOG.error("Fail to apply task.", e);
            Utils.runClosureInThread(task.getDone(), new Status(RaftError.EPERM, "Node is down."));
        }
    }

```

上面的apply方法中出现了一个LogEntry日志对象。在这个类中有个属性是id,也就是LogId对象，LogId对象有2个long类型属性，
分别是term和index。这两个值对分布式存储系统很重要。  
很多分布式系统都会有选举这一功能，当一个系统选举完成后，term就会增加1。有点类似美国总统任期。而index就好比总统颁发的
总统令的序号。只不过jraft中的这个序号index是一直递增的。比如说当前集群的term是5，index是21。某一时刻主节点无法正常
工作，然后集群通过选举选出了主节点。此时term就会变成6，index还是21（并不是变成0或者1）。此时客户端提交了一个简单的
put(k, v)操作，集群执行完put操作之后，集群的index就会变成22(term还是6)。  


### 2.3.3 第二个线程池
2.3.2结尾的apply方法中提到了一个线程池，线程池中的线程就是BatchEventProcessor。BatchEventProcessor实现了
Runnable接口。run方法如下

```java
    public void run()
    {
        if (!running.compareAndSet(false, true))
        {
            throw new IllegalStateException("Thread is already running");
        }
        sequenceBarrier.clearAlert();

        notifyStart();

        T event = null;
        long nextSequence = sequence.get() + 1L;
        try
        {
            while (true)
            {
                try
                {
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                    if (batchStartAware != null)
                    {
                        batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                    }

                    while (nextSequence <= availableSequence)
                    {

                        //这里是核心代码。从队列获取event并交给eventHandler处理，EventHandler
                        // 接口有多个实现，这里如何确定是哪个实现呢？ 还回到上一步的代码，也就是2.3.2
                        // 结尾的apply方法。上一步代码中有一个转换器，泛型是LogEntryAndClosure,所以
                        // eventHandler就是LogEntryAndClosureHandler。这是NodeImpl的一个内部类
                        event = dataProvider.get(nextSequence);
                        eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                        nextSequence++;
                    }

                    sequence.set(availableSequence);
                }
                catch (final TimeoutException e)
                {
                    notifyTimeout(sequence.get());
                }
                catch (final AlertException ex)
                {
                    if (!running.get())
                    {
                        break;
                    }
                }
                catch (final Throwable ex)
                {
                    exceptionHandler.handleEventException(ex, nextSequence, event);
                    sequence.set(nextSequence);
                    nextSequence++;
                }
            }
        }
        finally
        {
            notifyShutdown();
            running.set(false);
        }
    }

```

LogEntryAndClosureHandler.onEvent方法核心代码如下
```java
this.tasks.add(event);
//调用NodeImpl的executeApplyingTasks方法。
executeApplyingTasks(this.tasks);
```

NodeImpl.executeApplyingTasks方法内容如下
```java
    private void executeApplyingTasks(final List<LogEntryAndClosure> tasks) {
        this.writeLock.lock();
        try {
            final int size = tasks.size();
            if (this.state != State.STATE_LEADER) {
                final Status st = new Status();
                if (this.state != State.STATE_TRANSFERRING) {
                    st.setError(RaftError.EPERM, "Is not leader.");
                } else {
                    st.setError(RaftError.EBUSY, "Is transferring leadership.");
                }
                LOG.debug("Node {} can't apply, status={}.", getNodeId(), st);
                final List<LogEntryAndClosure> savedTasks = new ArrayList<>(tasks);
                Utils.runInThread(() -> {
                    for (int i = 0; i < size; i++) {
                        savedTasks.get(i).done.run(st);
                    }
                });
                return;
            }
            final List<LogEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final LogEntryAndClosure task = tasks.get(i);
                if (task.expectedTerm != -1 && task.expectedTerm != this.currTerm) {
                    LOG.debug("Node {} can't apply task whose expectedTerm={} doesn't match currTerm={}.", getNodeId(),
                        task.expectedTerm, this.currTerm);
                    if (task.done != null) {
                        final Status st = new Status(RaftError.EPERM, "expected_term=%d doesn't match current_term=%d",
                            task.expectedTerm, this.currTerm);
                        Utils.runClosureInThread(task.done, st);
                    }
                    continue;
                }
                //这里的task就是前面提到的信息载体event，task.done就是前面提到的层层封装的回调closure。
                // 这里是把closure放入了一个队列中，后面会用到
                if (!this.ballotBox.appendPendingTask(this.conf.getConf(),
                    this.conf.isStable() ? null : this.conf.getOldConf(), task.done)) {
                    Utils.runClosureInThread(task.done, new Status(RaftError.EINTERNAL, "Fail to append task."));
                    continue;
                }
                // set task entry info before adding to list.
                //往信息载体上增加一些信息
                task.entry.getId().setTerm(this.currTerm);
                task.entry.setType(EnumOutter.EntryType.ENTRY_TYPE_DATA);
                entries.add(task.entry);
            }
            //主节点和从节点协商是否执行客户端提交的操作请求，如果半数以上同意，集群就会执行客户端提交的操作请求。
            // 这里注意下第二个参数LeaderStableClosure对象。这也是个回调closure。他的作用就是收集各个节点
            // 的意见或者说是投票（也就是同意或者不同意），并做出最终决定。
            this.logManager.appendEntries(entries, new LeaderStableClosure(entries));
            // update conf.first
            checkAndSetConfiguration(true);
        } finally {
            this.writeLock.unlock();
        }
    }

```

上面的this.logManager.appendEntries 就是LogManagerImpl.appendEntries方法，内容如下

```java
    /**
     * 对于主节点来说，这个方法的作用就是将当前的主线任务拆分成2个支线任务。这两个支线分别在2个线程池中执行
     *（这个方法在从节点中也会被调用，只是方法第二个入参不同，逻辑不同，先不讨论）
     *
     * 支线1：主节点向每个从节点发起协商请求，从节点收到后，会将自己的意见（也可以理解为投票）响应
     * 给主节点
     *
     * 支线2：主节点判断赞成的票数是否超过集群总节点数的一半（主节点自己也参与投票），如果超过一半，
     * 集群就会应用客户端提交的操作请求。反之则不会应用。在主节点中第二个参数实际类型是
     * LeaderStableClosure（从节点中出入的是FollowerStableClosure），支线1的逻辑就在这个
     * closure的run方法中。
     *
     *
     *
     */
    public void appendEntries(final List<LogEntry> entries, final StableClosure done) {
        Requires.requireNonNull(done, "done");
        if (this.hasError) {
            entries.clear();
            Utils.runClosureInThread(done, new Status(RaftError.EIO, "Corrupted LogStorage"));
            return;
        }
        boolean doUnlock = true;
        this.writeLock.lock();
        try {
            if (!entries.isEmpty() && !checkAndResolveConflict(entries, done)) {
                // If checkAndResolveConflict returns false, the done will be called in it.
                entries.clear();
                return;
            }
            for (int i = 0; i < entries.size(); i++) {
                final LogEntry entry = entries.get(i);
                // Set checksum after checkAndResolveConflict
                if (this.raftOptions.isEnableLogEntryChecksum()) {
                    entry.setChecksum(entry.checksum());
                }
                if (entry.getType() == EntryType.ENTRY_TYPE_CONFIGURATION) {
                    Configuration oldConf = new Configuration();
                    if (entry.getOldPeers() != null) {
                        oldConf = new Configuration(entry.getOldPeers(), entry.getOldLearners());
                    }
                    final ConfigurationEntry conf = new ConfigurationEntry(entry.getId(),
                        new Configuration(entry.getPeers(), entry.getLearners()), oldConf);
                    this.configManager.add(conf);
                }
            }
            if (!entries.isEmpty()) {
                done.setFirstLogIndex(entries.get(0).getId().getIndex());
                this.logsInMemory.addAll(entries);
            }
            done.setEntries(entries);

            //支线2 ------------------------------start----------------------------------
            //下面这块代码就是上面提到的支线2，这段代码在NodeImpl.apply方法提到过，从translator
            // 泛型类型可以知道eventHandler是StableClosureEventHandler。最终执行的其实就是
            // done这个closure的run方法
            int retryTimes = 0;
            final EventTranslator<StableClosureEvent> translator = (event, sequence) -> {
                event.reset();
                event.type = EventType.OTHER;
                event.done = done;
            };
            while (true) {
                if (tryOfferEvent(done, translator)) {
                    break;
                } else {
                    retryTimes++;
                    if (retryTimes > APPEND_LOG_RETRY_TIMES) {
                        reportError(RaftError.EBUSY.getNumber(), "LogManager is busy, disk queue overload.");
                        return;
                    }
                    ThreadHelper.onSpinWait();
                }
            }
            //支线2 ------------------------------end----------------------------------
            doUnlock = false;
            //wakeupAllWaiter这个方法就是支线1
            if (!wakeupAllWaiter(this.writeLock)) {
                notifyLastLogIndexListeners();
            }
        } finally {
            if (doUnlock) {
                this.writeLock.unlock();
            }
        }
    }

```

#### 2.3.3.1 支线1 集群投票

支线1就是主节点向从节点发出投票请求，投票的内容就是客户端发起的操作请求是否要在当前集群中执行。从节点会根据自身进行判断是否要同意，
然后会把同意与否响应给主节点。
LogManagerImpl.wakeupAllWaiter方法内容如下

```java
    private boolean wakeupAllWaiter(final Lock lock) {
        //如果是从节点，waitMap为空，这里直接返回了，下面的代码不会执行。
        // 如果是主节点，waitMap不为空，继续往下执行
        if (this.waitMap.isEmpty()) {
            lock.unlock();
            return false;
        }
        final List<WaitMeta> wms = new ArrayList<>(this.waitMap.values());
        final int errCode = this.stopped ? RaftError.ESTOP.getNumber() : RaftError.SUCCESS.getNumber();
        this.waitMap.clear();
        lock.unlock();

        //waiterCount可以理解为从节点的个数。
        final int waiterCount = wms.size();
        for (int i = 0; i < waiterCount; i++) {
            final WaitMeta wm = wms.get(i);
            wm.errorCode = errCode;
            //创建一个投票请求任务，并且将这个任务交给线程池处理。
            // 任务的内容就是向从节点发送投票请求，投票决定是否执行客户端提交的请求。
            // 从节点经过判断会响应主节点自己的意见（同意或者不同意）。这里的wm
            // 可以看做一个从节点，里面有从节点的信息（也就是里面含有一个Replicator
            // 对象，这个Replicator就是其他节点的副本，里面包含了其他节点的完整信息）
            Utils.runInThread(() -> runOnNewLog(wm));
        }
        return true;
    }
```

上面提到的投票请求任务执行，调用方法路径如下

```java
com.alipay.sofa.jraft.storage.impl.LogManagerImpl.runOnNewLog
com.alipay.sofa.jraft.core.Replicator.lambda$waitMoreEntries$8
com.alipay.sofa.jraft.core.Replicator.continueSending
com.alipay.sofa.jraft.core.Replicator.sendEntries
```

从上面方法调用可以看出，是交给了Replicator对象处理，Replicator就代表了某一个从节点在当前主节点的副本。
我们直接看第三行Replicator.sendEntries方法（Replicator中有2个sendEntries方法，一个无参，一个有参
。这里是无参，然后无参方法又会调用有参方法）。这里基本上就是噩梦般的开始了，因为往后会涉及到大量的index变量。
下面是sendEntries方法。这里声明一下term和index。term如果不特别强调，就只集群当前的term。集群的index
下面就称作“集群index”

```java
    void sendEntries() {
        boolean doUnlock = true;
        try {
            long prevSendIndex = -1;
            while (true) {
                //获取nextSendingIndex，这个值从名称看next，其实就是“集群index”加1得到的
                final long nextSendingIndex = getNextSendIndex();
                if (nextSendingIndex > prevSendIndex) {
                    //sendEntries(nextSendingIndex)会被执行
                    if (sendEntries(nextSendingIndex)) {
                        prevSendIndex = nextSendingIndex;
                    } else {
                        doUnlock = false;
                        // id already unlock in sendEntries when it returns false.
                        break;
                    }
                } else {
                    break;
                }
            }
        } finally {
            if (doUnlock) {
                this.id.unlock();
            }
        }

    }

```