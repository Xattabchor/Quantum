#include <e32base.h>
#include <e32std.h>
#include <gdi.h>
#include <w32std.h>
#include <mdaaudiosampleplayer.h>

// Three-mode diagnostic, not game audio. One fresh worker per run.
namespace {
const TUint KGroup=1, KWindow=2;

_LIT(KLibrary,"mediaclientaudio.dll");
_LIT(KShot,"C:\\data\\q41_ct2_shot.wav");
typedef void (*TOpenFile)(CMdaAudioPlayerUtility*,const TDesC&);
enum { EOpen, EInit, EPlayEnter, EPlayReturn, EComplete, ECompleteError,
       ETicks, ETimeout, EResult, EStage, ECleanup, ECount };

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
        iShared.Set(EInit,aError); if(aError!=KErrNone){iResult=aError; CActiveScheduler::Stop();return;} if(iShared.mode==3)Play();
    }
    void Play() { iShared.Increment(EPlayEnter); iPlayer->Play(); iShared.Increment(EPlayReturn); }
    void MapcPlayComplete(TInt aError) override { iShared.Increment(EComplete); iShared.Set(ECompleteError,aError); iResult=aError; }
    TInt Result() const { return iResult; }
private:
    TShared& iShared; CMdaAudioPlayerUtility* iPlayer; RLibrary iLibrary; TOpenFile iOpen; TBool iLoaded; TInt iResult;
};
// Common 4 second observation timer, started before the mode-specific work.
class CObservation : public CActive {
public:
    static void operator delete(void* aPtr) { User::Free(aPtr); }
    explicit CObservation(TShared& s):CActive(EPriorityStandard),iShared(s),iCreated(EFalse) { CActiveScheduler::Add(this); }
    ~CObservation() { Cancel(); if(iCreated)iTimer.Close(); }
    TInt Start() { TInt r=iTimer.CreateLocal(); if(r!=KErrNone)return r; iCreated=ETrue; iTimer.After(iStatus,TTimeIntervalMicroSeconds32(4000000)); SetActive(); return KErrNone; }
private:
    void RunL() override { iShared.Set(ETimeout,iStatus.Int()==KErrNone?1:iStatus.Int()); CActiveScheduler::Stop(); }
    void DoCancel() override { iTimer.Cancel(); }
    TShared& iShared; RTimer iTimer; TBool iCreated;
};
// The scheduler wraps RunL in its Symbian leave trap and calls RunError on leave.
// A one-shot kernel timer enters the scheduler; no self-completion or worker watchdog.
class CBootstrap : public CActive {
public:
    static void operator delete(void* aPtr) { User::Free(aPtr); }
    explicit CBootstrap(CSession& aSession,CObservation& aObservation,TShared& aShared):CActive(EPriorityStandard),iSession(aSession),iObservation(aObservation),iShared(aShared),iCreated(EFalse) { CActiveScheduler::Add(this); }
    ~CBootstrap() { Cancel(); if(iCreated)iTimer.Close(); }
    TInt Post() { TInt r=iTimer.CreateLocal(); if(r!=KErrNone)return r; iCreated=ETrue; iTimer.After(iStatus,TTimeIntervalMicroSeconds32(1)); SetActive(); return KErrNone; }
private:
    void RunL() override { TInt r=iObservation.Start(); if(r==KErrNone && iShared.mode!=1)r=iSession.Start(); if(r!=KErrNone)iSession.Fail(r); }
    TInt RunError(TInt aError) override { iSession.Fail(aError); return KErrNone; }
    void DoCancel() override { iTimer.Cancel(); }
    CSession& iSession; CObservation& iObservation; TShared& iShared; RTimer iTimer; TBool iCreated;
};
TInt worker(TAny* aData) {
    TShared& s=*static_cast<TShared*>(aData); CTrapCleanup* cleanup=CTrapCleanup::New(); if(!cleanup)return KErrNoMemory;
    TInt r=User::SetCritical(User::ENotCritical); CActiveScheduler* scheduler=0; CSession* session=0; CBootstrap* bootstrap=0; CObservation* observation=0;
    if(r==KErrNone){scheduler=new CActiveScheduler; if(!scheduler)r=KErrNoMemory;}
    if(r==KErrNone){CActiveScheduler::Install(scheduler); session=new CSession(s); if(!session)r=KErrNoMemory;}
    if(r==KErrNone){observation=new CObservation(s); if(!observation)r=KErrNoMemory;}
    if(r==KErrNone){bootstrap=new CBootstrap(*session,*observation,s); if(!bootstrap)r=KErrNoMemory;}
    if(r==KErrNone){r=bootstrap->Post(); if(r==KErrNone){CActiveScheduler::Start(); r=session->Result();}}
    s.Set(EResult,r); s.Set(ECleanup,1); delete bootstrap; s.Set(ECleanup,2); delete observation; s.Set(ECleanup,3); delete session; s.Set(ECleanup,4); CActiveScheduler::Install(0); delete scheduler; s.Set(ECleanup,5); delete cleanup; s.Set(ECleanup,6); return r;
}
void draw(CWsScreenDevice& screen,CWindowGc& gc,const TSize& size,TShared& shared,RThread& thread,TBool started,TInt startError) {
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush); gc.SetPenStyle(CGraphicsContext::ENullPen); gc.SetBrushColor(TRgb(12,20,32)); gc.Clear(TRect(TPoint(0,0),size));
    CFont* font=0; if(screen.GetNearestFontInPixels(font,TFontSpec(_L("Series 60 Sans"),13))!=KErrNone)return;
    gc.UseFont(font); gc.SetPenStyle(CGraphicsContext::ESolidPen); gc.SetPenColor(TRgb(240,244,250));
    TInt t[ECount]; shared.Snapshot(t); TBuf<100> l;
    gc.DrawText(_L("MDA CONTROL v1.0.1"),TPoint(8,24));
    l.Format(_L("Mode: %d  Start: %d"),shared.mode,startError); gc.DrawText(l,TPoint(8,45));
    if(started){l.Format(_L("Exit type %d reason %d"),(TInt)thread.ExitType(),thread.ExitReason());gc.DrawText(l,TPoint(8,66)); if(thread.ExitType()==EExitPanic){TExitCategoryName c=thread.ExitCategory();gc.DrawText(c,TPoint(8,86));}}
    l.Format(_L("O:%d I:%d P:%d/%d"),t[EOpen],t[EInit],t[EPlayEnter],t[EPlayReturn]);gc.DrawText(l,TPoint(8,108));
    l.Format(_L("C:%d E:%d K:%d"),t[EComplete],t[ECompleteError],t[ECleanup]);gc.DrawText(l,TPoint(8,128));
    l.Format(_L("TO:%d R:%d S:%d"),t[ETimeout],t[EResult],t[EStage]);gc.DrawText(l,TPoint(8,148));
    gc.DrawText(_L("1:timer 2:open 3:play"),TPoint(8,172)); gc.DrawText(_L("Back/Esc: exit"),TPoint(8,192));
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
        if(key->iCode>='1' && key->iCode<='3' && (!started || logon!=KRequestPending)) {
            if(started){thread.Close();started=EFalse;}
            shared.mutex.Wait(); shared.mode=key->iCode-'0';for(TInt i=0;i<ECount;++i)shared.trace[i]=0;shared.trace[EInit]=9999;shared.trace[ECompleteError]=9999;shared.mutex.Signal();
            TBuf<48> threadName; threadName.Format(_L("Q41Ct2-%08x-%08x"),User::TickCount(),++runNumber);
            startError=thread.Create(threadName,worker,0x8000,0x10000,0x200000,&shared);
            if(startError==KErrNone){started=ETrue;thread.Logon(logon);thread.Resume();User::After(5000000);}
        }
        r=redraw(ws,win,screen,gc,size,shared,thread,started,startError);
    }
    if(started){
        // Only on application exit: terminate a worker that has not finished.
        // The observation timer cannot interrupt a synchronous hang; terminate on exit.
        if(logon==KRequestPending){thread.Kill(KErrCancel);User::WaitForRequest(logon);}
        thread.Close();
    }
    win.Close();group.Close();ws.Flush();shared.mutex.Close();return r;
}
}
int main(){CTrapCleanup* cleanup=CTrapCleanup::New();if(!cleanup)return KErrNoMemory;CActiveScheduler* scheduler=new CActiveScheduler;if(!scheduler){delete cleanup;return KErrNoMemory;}CActiveScheduler::Install(scheduler);RWsSession ws;TInt r=ws.Connect();if(r==KErrNone){CWsScreenDevice screen(ws);r=screen.Construct();if(r==KErrNone){CWindowGc gc(&screen);r=gc.Construct();if(r==KErrNone)r=run(ws,screen,gc);}ws.Close();}CActiveScheduler::Install(0);delete scheduler;delete cleanup;return r;}
