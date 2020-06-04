package zmq.io;

//  Abstract interface to be implemented by various engines.
//  所有 engines 的接口
public interface IEngine
{
    //  Plug the engine to the session.
    //  将引擎插入会话。
    void plug(IOThread ioThread, SessionBase session);

    //  Terminate and deallocate the engine. Note that 'detached'
    //  events are not fired on termination.
    //  终止并取消分配引擎。 请注意，终止时不会触发“分离”事件。
    void terminate();

    //  This method is called by the session to signal that more
    //  messages can be written to the pipe.
    //  session 会调用此方法，以发出可以将更多消息写入管道的信号。
    void restartInput();

    //  This method is called by the session to signal that there
    //  are messages to send available.
    void restartOutput();

    void zapMsgAvailable();
}
