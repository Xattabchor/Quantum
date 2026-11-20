#include <e32base.h>
#include <e32std.h>
#include <gdi.h>
#include <w32std.h>
#include <mdaaudiosampleplayer.h>

// Isolated A/B diagnostic, not game audio. One worker and one playback per run.
namespace {
const TUint KGroup=1, KWindow=2;

_LIT(KLibrary,"mediaclientaudio.dll");
_LIT(KShot,"C:\\data\\q41_min_shot.wav");
typedef void (*TOpenFile)(CMdaAudioPlayerUtility*,const TDesC&);
enum { EOpen, EInit, EPlayEnter, EPlayReturn, EComplete, ECompleteError,
       ETicks, ETimeout, EResult, EStage, ECount };

struct TShared {
    RMutex mutex;
    TInt mode;
    TInt trace[ECount];
    void Set(TInt aIndex,TInt aValue) { mutex.Wait(); trace[aIndex]=aValue; mutex.Signal(); }
    void Increment(TInt aIndex) { mutex.Wait(); ++trace[aIndex]; mutex.Signal(); }
    void Snapshot(TInt* aOut) { mutex.Wait(); for(TInt i=0;i<ECount;++i)aOut[i]=trace[i]; mutex.Signal(); }
};
class CSession;

class CSession : public MMdaAudioPlayerCallback {
public:
    explicit CSession(TShared& aShared):iShared(aShared),iPlayer(0),iOpen(0),iLoaded(EFalse),iResult(KErrNone) {}
    ~CSession() { delete iPlayer; if(iLoaded)iLibrary.Close(); }
    TInt Start() {
        iShared.Set(EStage,1);
        iShared.Set(EStage,2);
        TInt r=iLibrary.Load(KLibrary); if(r!=KErrNone)return r; iLoaded=ETrue;
        iShared.Set(EStage,3);
        iOpen=reinterpret_cast<TOpenFile>(iLibrary.Lookup(42)); if(!iOpen)return KErrNotFound;
        iShared.Set(EStage,4);
        iPlayer=CMdaAudioPlayerUtility::NewL(*this); if(!iPlayer)return KErrNoMemory;
        iShared.Set(EStage,5);
        iShared.Set(EOpen,1); iOpen(iPlayer,KShot);
        iShared.Set(EStage,6); return KErrNone;
    }
    void Fail(TInt aError) { iResult=aError; iShared.Set(EResult,aError); CActiveScheduler::Stop(); }
    void MapcInitComplete(TInt aError,const TTimeIntervalMicroSeconds&) override {
        iShared.Set(EInit,aError); if(aError!=KErrNone){iResult=aError; CActiveScheduler::Stop();return;} Play();
    }
    void Play() { iShared.Increment(EPlayEnter); iPlayer->Play(); iShared.Increment(EPlayReturn); }
    void MapcPlayComplete(TInt aError) override { iShared.Increment(EComplete); iShared.Set(ECompleteError,aError); iResult=aError; CActiveScheduler::Stop(); }
    TInt Result() const { return iResult; }
private:
    TShared& iShared; CMdaAudioPlayerUtility* iPlayer; RLibrary iLibrary; TOpenFile iOpen; TBool iLoaded; TInt iResult;
};
// The scheduler wraps RunL in its Symbian leave trap and calls RunError on leave.
// A one-shot kernel timer enters the scheduler; no self-completion or worker watchdog.
class CBootstrap : public CActive {
public:
    explicit CBootstrap(CSession& aSession):CActive(EPriorityStandard),iSession(aSession),iCreated(EFalse) { CActiveScheduler::Add(this); }
    ~CBootstrap() { Cancel(); if(iCreated)iTimer.Close(); }
    TInt Post() { TInt r=iTimer.CreateLocal(); if(r!=KErrNone)return r; iCreated=ETrue; iTimer.After(iStatus,TTimeIntervalMicroSeconds32(1)); SetActive(); return KErrNone; }
private:
    void RunL() override { TInt r=iSession.Start(); if(r!=KErrNone)iSession.Fail(r); }
    TInt RunError(TInt aError) override { iSession.Fail(aError); return KErrNone; }
    void DoCancel() override { iTimer.Cancel(); }
    CSession& iSession; RTimer iTimer; TBool iCreated;
};
TInt worker(TAny* aData) {
    TShared& s=*static_cast<TShared*>(aData); CTrapCleanup* cleanup=CTrapCleanup::New(); if(!cleanup)return KErrNoMemory;
    TInt r=User::SetCritical(User::ENotCritical); CActiveScheduler* scheduler=0; CSession* session=0; CBootstrap* bootstrap=0;
    if(r==KErrNone){scheduler=new CActiveScheduler; if(!scheduler)r=KErrNoMemory;}
    if(r==KErrNone){CActiveScheduler::Install(scheduler); session=new CSession(s); if(!session)r=KErrNoMemory;}
    if(r==KErrNone){bootstrap=new CBootstrap(*session); if(!bootstrap)r=KErrNoMemory;}
    if(r==KErrNone){r=bootstrap->Post(); if(r==KErrNone){CActiveScheduler::Start(); r=session->Result();}}
    s.Set(EResult,r); delete bootstrap; delete session; CActiveScheduler::Install(0); delete scheduler; delete cleanup; return r;
}
void draw(CWsScreenDevice& screen,CWindowGc& gc,const TSize& size,TShared& shared,RThread& thread,TBool started,TInt startError) {
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush); gc.SetPenStyle(CGraphicsContext::ENullPen); gc.SetBrushColor(TRgb(12,20,32)); gc.Clear(TRect(TPoint(0,0),size));
    CFont* font=0; if(screen.GetNearestFontInPixels(font,TFontSpec(_L("Series 60 Sans"),13))!=KErrNone)return;
    gc.UseFont(font); gc.SetPenStyle(CGraphicsContext::ESolidPen); gc.SetPenColor(TRgb(240,244,250));
    TInt t[ECount]; shared.Snapshot(t); TBuf<100> l;
    gc.DrawText(_L("MDA MINIMAL v1.0.0"),TPoint(8,24));
    l.Format(_L("Mode: %d  Start: %d"),shared.mode,startError); gc.DrawText(l,TPoint(8,45));
    if(started){l.Format(_L("Exit type %d reason %d"),(TInt)thread.ExitType(),thread.ExitReason());gc.DrawText(l,TPoint(8,66)); if(thread.ExitType()==EExitPanic){TExitCategoryName c=thread.ExitCategory();gc.DrawText(c,TPoint(8,86));}}
    l.Format(_L("O:%d I:%d P:%d/%d"),t[EOpen],t[EInit],t[EPlayEnter],t[EPlayReturn]);gc.DrawText(l,TPoint(8,108));
    l.Format(_L("C:%d E:%d T:%d"),t[EComplete],t[ECompleteError],t[ETicks]);gc.DrawText(l,TPoint(8,128));
    l.Format(_L("TO:%d R:%d S:%d"),t[ETimeout],t[EResult],t[EStage]);gc.DrawText(l,TPoint(8,148));
    gc.DrawText(_L("1: play   0: refresh"),TPoint(8,172)); gc.DrawText(_L("Back/Esc: exit"),TPoint(8,192));
    gc.DiscardFont(); screen.ReleaseFont(font);
}
TInt redraw(RWsSession& ws,RWindow& win,CWsScreenDevice& screen,CWindowGc& gc,const TSize& size,TShared& s,RThread& thread,TBool started,TInt error) {
    TRequestStatus status; ws.RedrawReady(&status);win.Invalidate();ws.Flush();User::WaitForRequest(status); TInt r=status.Int();
    if(r==KErrNone){TWsRedrawEvent ev;ws.GetRedraw(ev);if(ev.Handle()==KWindow){win.BeginRedraw(ev.Rect());gc.Activate(win);draw(screen,gc,size,s,thread,started,error);gc.Deactivate();win.EndRedraw();}}
    ws.RedrawReadyCancel();ws.Flush();return r;
}
TInt run(RWsSession& ws,CWsScreenDevice& screen,CWindowGc& gc) {
    TShared shared; TInt r=shared.mutex.CreateLocal();if(r!=KErrNone)return r; shared.mode=0;for(TInt i=0;i<ECount;++i)shared.trace[i]=0;shared.trace[EInit]=9999;shared.trace[ECompleteError]=9999;
    RWindowGroup group(ws);r=group.Construct(KGroup,ETrue);if(r!=KErrNone){shared.mutex.Close();return r;}
    RWindow win(ws);r=win.Construct(group,KWindow);if(r!=KErrNone){group.Close();shared.mutex.Close();return r;}
    TSize size=screen.SizeInPixels();group.SetOrdinalPosition(0);win.SetExtent(TPoint(0,0),size);win.SetVisible(ETrue);win.Activate();
    RThread thread; TRequestStatus logon;TBool started=EFalse;TInt startError=KErrNone; TUint runNumber=0;
    r=redraw(ws,win,screen,gc,size,shared,thread,started,startError);
    while(r==KErrNone) {
        TRequestStatus event;ws.EventReady(&event);User::WaitForRequest(event);r=event.Int();if(r!=KErrNone)break;
        TWsEvent ev;ws.GetEvent(ev);if(ev.Handle()!=KGroup || ev.Type()!=EEventKey)continue;
        TKeyEvent* key=ev.Key();if(key->iCode==EKeyEscape || key->iScanCode==EStdKeyEscape)break;
        if(key->iCode=='1' && (!started || logon!=KRequestPending)) {
            if(started){thread.Close();started=EFalse;}
            shared.mutex.Wait(); shared.mode=key->iCode-'0';for(TInt i=0;i<ECount;++i)shared.trace[i]=0;shared.trace[EInit]=9999;shared.trace[ECompleteError]=9999;shared.mutex.Signal();
            TBuf<48> threadName; threadName.Format(_L("Q41Min-%08x-%08x"),User::TickCount(),++runNumber);
            startError=thread.Create(threadName,worker,0x8000,0x10000,0x200000,&shared);
            if(startError==KErrNone){started=ETrue;thread.Logon(logon);thread.Resume();User::After(3000000);}
        }
        r=redraw(ws,win,screen,gc,size,shared,thread,started,startError);
    }
    if(started){
        // Only on application exit: terminate a worker that has not finished.
        // No worker watchdog is armed; a stuck test remains pending until exit.
        if(logon==KRequestPending){thread.Kill(KErrCancel);User::WaitForRequest(logon);}
        thread.Close();
    }
    win.Close();group.Close();ws.Flush();shared.mutex.Close();return r;
}
}
int main(){CTrapCleanup* cleanup=CTrapCleanup::New();if(!cleanup)return KErrNoMemory;CActiveScheduler* scheduler=new CActiveScheduler;if(!scheduler){delete cleanup;return KErrNoMemory;}CActiveScheduler::Install(scheduler);RWsSession ws;TInt r=ws.Connect();if(r==KErrNone){CWsScreenDevice screen(ws);r=screen.Construct();if(r==KErrNone){CWindowGc gc(&screen);r=gc.Construct();if(r==KErrNone)r=run(ws,screen,gc);}ws.Close();}CActiveScheduler::Install(0);delete scheduler;delete cleanup;return r;}
