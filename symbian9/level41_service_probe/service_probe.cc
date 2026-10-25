#include <e32base.h>
#if !defined(QUANTUM_SERVICE_MATH_ONLY)
#include <f32file.h>
#endif
#if !defined(QUANTUM_SERVICE_FILESERVER_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
#include <e32math.h>
#include <math.h>
#endif
#include <gdi.h>
#include <w32std.h>

#if defined(QUANTUM_SERVICE_MATH_ONLY) && \
    (defined(QUANTUM_SERVICE_FILESERVER_ONLY) || \
     defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) || \
     defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED))
#error Select at most one service probe profile
#endif
#if defined(QUANTUM_SERVICE_FILESERVER_ONLY) && \
    (defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) || \
     defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED))
#error Select at most one service probe profile
#endif
#if defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) && \
    defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
#error Select at most one service probe profile
#endif

namespace {

struct TServiceResults {
    TInt fsConnect;
    TInt fileOpen;
    TInt fileSize;
    TInt fileRead;
    TBool fsConnectAttempted;
    TInt mathSqrt;
    TInt mathSin;
    TInt mathCos;
    TInt libmSqrt;

    TServiceResults()
        : fsConnect(KErrNotReady), fileOpen(KErrNotReady),
          fileSize(KErrNotReady), fileRead(KErrNotReady),
          fsConnectAttempted(EFalse), mathSqrt(KErrNotReady),
          mathSin(KErrNotReady),
          mathCos(KErrNotReady), libmSqrt(KErrNotReady)
    {
    }
};

#if !defined(QUANTUM_SERVICE_MATH_ONLY)
void closeFileServerSession(RFs& fileServer)
{
    // RFs::Close was added for tracing and was absent from earlier OS
    // versions; close via RHandleBase to avoid that later EFSRV import.
    static_cast<RHandleBase&>(fileServer).Close();
}

void probeFileServer(TServiceResults& results)
{
    RFs fileServer;
    results.fsConnectAttempted = ETrue;
    results.fsConnect = fileServer.Connect();
    if (results.fsConnect != KErrNone) return;
#if defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) || \
    defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
    closeFileServerSession(fileServer);
    return;
#endif

    TFileName executable;
    executable.Copy(RProcess().FileName());
    RFile file;
    results.fileOpen = file.Open(
        fileServer, executable, EFileRead | EFileShareReadersOnly);
    if (results.fileOpen == KErrNone) {
        TInt size = 0;
        results.fileSize = file.Size(size);
        if (results.fileSize == KErrNone && size >= 4) {
            TBuf8<4> header;
            results.fileRead = file.Read(header, 4);
        }
        file.Close();
    }
    closeFileServerSession(fileServer);
}
#endif

#if !defined(QUANTUM_SERVICE_FILESERVER_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
void probeMath(TServiceResults& results)
{
    TReal root = 0.0;
    TReal sine = 0.0;
    TReal cosine = 0.0;
    results.mathSqrt = ::Math::Sqrt(root, static_cast<TReal>(16.0));
    results.mathSin = ::Math::Sin(sine, static_cast<TReal>(0.5));
    results.mathCos = ::Math::Cos(cosine, static_cast<TReal>(0.5));

    TReal input = 16.0;
    const TReal libmRoot = ::sqrt(input);
    results.libmSqrt =
        (libmRoot > 3.9 && libmRoot < 4.1) ? KErrNone : KErrGeneral;
}
#endif

void drawResults(CWsScreenDevice& screen,
                 CWindowGc& gc,
                 const TSize& size,
                 const TServiceResults& result)
{
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(0, 72, 120));
    gc.DrawRect(TRect(TPoint(0, 0), size));

    gc.SetPenStyle(CGraphicsContext::ESolidPen);
    gc.SetPenColor(TRgb(255, 255, 255));
    CFont* font = 0;
    if (screen.GetNearestFontInPixels(
            font, TFontSpec(_L("Series 60 Sans"), 12)) != KErrNone) {
        return;
    }

    gc.UseFont(font);
    TBuf<64> line;
#if defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
    gc.DrawText(_L("RFs DEFERRED PROBE"), TPoint(10, 24));
    if (result.fsConnectAttempted) {
        line.Format(_L("RFs connect: %d"), result.fsConnect);
        gc.DrawText(line, TPoint(10, 52));
    } else {
        gc.DrawText(_L("Press key to call RFs::Connect"),
                    TPoint(10, 52));
    }
#elif defined(QUANTUM_SERVICE_FS_CONNECT_ONLY)
    gc.DrawText(_L("RFs CONNECT PROBE"), TPoint(10, 24));
    line.Format(_L("RFs connect: %d"), result.fsConnect);
    gc.DrawText(line, TPoint(10, 52));
#elif defined(QUANTUM_SERVICE_FILESERVER_ONLY)
    gc.DrawText(_L("FILE SERVER PROBE"), TPoint(10, 24));
    line.Format(_L("RFs connect: %d  file open: %d"),
                result.fsConnect, result.fileOpen);
    gc.DrawText(line, TPoint(10, 52));
    line.Format(_L("file size: %d  read: %d"),
                result.fileSize, result.fileRead);
    gc.DrawText(line, TPoint(10, 76));
#elif defined(QUANTUM_SERVICE_MATH_ONLY)
    gc.DrawText(_L("MATH PROBE"), TPoint(10, 24));
    line.Format(_L("Math sqrt/sin/cos: %d/%d/%d"),
                result.mathSqrt, result.mathSin, result.mathCos);
    gc.DrawText(line, TPoint(10, 52));
    line.Format(_L("libm sqrt: %d"), result.libmSqrt);
    gc.DrawText(line, TPoint(10, 76));
#else
    gc.DrawText(_L("LEVEL 41 API PROBE"), TPoint(10, 24));
    line.Format(_L("RFs connect: %d  file open: %d"),
                result.fsConnect, result.fileOpen);
    gc.DrawText(line, TPoint(10, 52));
    line.Format(_L("file size: %d  read: %d"),
                result.fileSize, result.fileRead);
    gc.DrawText(line, TPoint(10, 76));
    line.Format(_L("Math sqrt/sin/cos: %d/%d/%d"),
                result.mathSqrt, result.mathSin, result.mathCos);
    gc.DrawText(line, TPoint(10, 100));
    line.Format(_L("libm sqrt: %d"), result.libmSqrt);
    gc.DrawText(line, TPoint(10, 124));
#endif
#if defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
    if (result.fsConnectAttempted) {
        gc.DrawText(_L("Press any key to exit"),
                    TPoint(10, size.iHeight - 12));
    } else {
        gc.DrawText(_L("Press any key to run test"),
                    TPoint(10, size.iHeight - 12));
    }
#else
    gc.DrawText(_L("Press any key to exit"),
                TPoint(10, size.iHeight - 12));
#endif
    gc.DiscardFont();
    screen.ReleaseFont(font);
}

TInt waitForKey(RWsSession& session, TUint windowHandle)
{
    TRequestStatus eventStatus;
    session.EventReady(&eventStatus);
    for (;;) {
        User::WaitForRequest(eventStatus);
        const TInt result = eventStatus.Int();
        if (result != KErrNone) return result;

        TWsEvent event;
        session.GetEvent(event);
        if (event.Handle() == windowHandle && event.Type() == EEventKey) {
            return KErrNone;
        }
        session.EventReady(&eventStatus);
    }
}

TInt redrawResults(RWsSession& session,
                   CWsScreenDevice& screen,
                   RWindow& window,
                   const TSize& size,
                   const TServiceResults& results)
{
    TRequestStatus redrawStatus;
    session.RedrawReady(&redrawStatus);
    window.Invalidate();
    session.Flush();
    User::WaitForRequest(redrawStatus);
    TInt result = redrawStatus.Int();
    if (result == KErrNone) {
        TWsRedrawEvent redraw;
        session.GetRedraw(redraw);
        if (redraw.Handle() == 2) {
            window.BeginRedraw(redraw.Rect());
            CWindowGc gc(&screen);
            result = gc.Construct();
            if (result == KErrNone) {
                gc.Activate(window);
                drawResults(screen, gc, size, results);
                gc.Deactivate();
                window.EndRedraw();
            }
        }
    }
    session.RedrawReadyCancel();
    session.Flush();
    return result;
}

} // namespace

int main()
{
    TServiceResults results;
#if !defined(QUANTUM_SERVICE_MATH_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
    probeFileServer(results);
#endif
#if !defined(QUANTUM_SERVICE_FILESERVER_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_ONLY) && \
    !defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
    probeMath(results);
#endif

    RWsSession session;
    TInt result = session.Connect();
    if (result != KErrNone) return result;

    CWsScreenDevice screen(session);
    result = screen.Construct();
    if (result != KErrNone) {
        session.Close();
        return result;
    }

    const TSize size = screen.SizeInPixels();
    if (size.iWidth <= 0 || size.iHeight <= 0) {
        session.Close();
        return KErrArgument;
    }

    RWindowGroup group(session);
    result = group.Construct(1, ETrue);
    if (result != KErrNone) {
        session.Close();
        return result;
    }

    RWindow window(session);
    result = window.Construct(group, 2);
    if (result != KErrNone) {
        group.Close();
        session.Close();
        return result;
    }

    group.SetOrdinalPosition(0);
    window.SetExtent(TPoint(0, 0), size);
    window.SetVisible(ETrue);
    window.Activate();

    result = redrawResults(session, screen, window, size, results);

#if defined(QUANTUM_SERVICE_FS_CONNECT_DEFERRED)
    if (result == KErrNone) {
        result = waitForKey(session, 2);
        if (result == KErrNone) {
            probeFileServer(results);
            result = redrawResults(session, screen, window, size, results);
        }
        if (result == KErrNone) result = waitForKey(session, 2);
    }
#else
    if (result == KErrNone) result = waitForKey(session, 2);
#endif
    window.Close();
    group.Close();
    session.Flush();
    session.Close();
    return result;
}
