#include <e32base.h>
#include <e32std.h>
#include <gdi.h>
#include <w32std.h>
#include "quantum_level41_audio.h"

namespace {
const TUint KGroup = 1, KWindow = 2;
void draw(CWsScreenDevice& screen, CWindowGc& gc, const TSize& size,
          CQuantumLevel41Audio* audio, TInt error, TInt shots) {
    gc.SetBrushStyle(CGraphicsContext::ESolidBrush);
    gc.SetPenStyle(CGraphicsContext::ENullPen);
    gc.SetBrushColor(TRgb(12,20,32)); gc.Clear(TRect(TPoint(0,0),size));
    CFont* font=0;
    if (screen.GetNearestFontInPixels(font,TFontSpec(_L("Series 60 Sans"),13)) != KErrNone) return;
    gc.UseFont(font); gc.SetPenStyle(CGraphicsContext::ESolidPen); gc.SetPenColor(TRgb(240,244,250));
    gc.DrawText(_L("SHOT PROBE v1.0.1"),TPoint(8,30));
    TBuf<80> line;
    line.Format(_L("Shot commands: %d"),shots); gc.DrawText(line,TPoint(8,55));
    if (!audio) { line.Format(_L("Create error: %d"),error); gc.DrawText(line,TPoint(8,80)); }
    else if (!audio->WorkerAlive()) {
        line.Format(_L("Worker exit type %d reason %d"), (TInt)audio->WorkerExitType(),audio->WorkerExitReason());
        gc.DrawText(line,TPoint(8,80));
        TExitCategoryName cat=audio->WorkerExitCategory(); gc.DrawText(cat,TPoint(8,105));
    } else {
        audio->StatusLine(line); gc.DrawText(line,TPoint(8,80));
        gc.DrawText(_L("Worker running"),TPoint(8,105));
    }
    if (audio) {
        line.Format(_L("O:%d I:%d P:%d/%d"), audio->Trace(CQuantumLevel41Audio::EOpen), audio->Trace(CQuantumLevel41Audio::EInitError), audio->Trace(CQuantumLevel41Audio::EPlayCall), audio->Trace(CQuantumLevel41Audio::EPlayReturn));
        gc.DrawText(line,TPoint(8,130));
        line.Format(_L("C:%d E:%d H:%d"), audio->Trace(CQuantumLevel41Audio::ECompleteCount), audio->Trace(CQuantumLevel41Audio::ECompleteError), audio->Trace(CQuantumLevel41Audio::EHandled));
        gc.DrawText(line,TPoint(8,150));
    }
    gc.DrawText(_L("5: shot   Back/Esc: exit"),TPoint(8,180));
    gc.DiscardFont(); screen.ReleaseFont(font);
}
TInt redraw(RWsSession& ws,RWindow& window,CWsScreenDevice& screen,CWindowGc& gc,
            const TSize& size,CQuantumLevel41Audio* audio,TInt error,TInt shots) {
    TRequestStatus s; ws.RedrawReady(&s); window.Invalidate(); ws.Flush(); User::WaitForRequest(s);
    TInt r=s.Int(); if (r==KErrNone) { TWsRedrawEvent ev; ws.GetRedraw(ev);
        if (ev.Handle()==KWindow) { window.BeginRedraw(ev.Rect()); gc.Activate(window); draw(screen,gc,size,audio,error,shots); gc.Deactivate(); window.EndRedraw(); } }
    ws.RedrawReadyCancel(); ws.Flush(); return r;
}
TInt run(RWsSession& ws,CWsScreenDevice& screen,CWindowGc& gc) {
    RWindowGroup group(ws); TInt r=group.Construct(KGroup,ETrue); if(r!=KErrNone)return r;
    RWindow win(ws); r=win.Construct(group,KWindow); if(r!=KErrNone){group.Close();return r;}
    TSize size=screen.SizeInPixels(); group.SetOrdinalPosition(0); win.SetExtent(TPoint(0,0),size); win.SetVisible(ETrue); win.Activate();
    TInt error=KErrNone; CQuantumLevel41Audio* audio=CQuantumLevel41Audio::New(_L("C:"),error); TInt shots=0;
    r=redraw(ws,win,screen,gc,size,audio,error,shots);
    while(r==KErrNone) {
        TRequestStatus s; ws.EventReady(&s); User::WaitForRequest(s); r=s.Int(); if(r!=KErrNone)break;
        TWsEvent ev; ws.GetEvent(ev); if(ev.Handle()!=KGroup || ev.Type()!=EEventKey)continue;
        TKeyEvent* key=ev.Key(); if(key->iCode==EKeyEscape || key->iScanCode==EStdKeyEscape) break;
        if((key->iCode=='5' || key->iCode==EKeyEnter || key->iCode==EKeyDevice3) && audio && audio->WorkerAlive()) {
            audio->PlayShot(); ++shots; User::After(3000000);
        }
        r=redraw(ws,win,screen,gc,size,audio,error,shots);
    }
    delete audio; win.Close(); group.Close(); ws.Flush(); return r;
}
}
int main() {
    CTrapCleanup* cleanup=CTrapCleanup::New(); if(!cleanup)return KErrNoMemory;
    CActiveScheduler* scheduler=new CActiveScheduler; if(!scheduler){delete cleanup;return KErrNoMemory;} CActiveScheduler::Install(scheduler);
    RWsSession ws; TInt r=ws.Connect(); if(r==KErrNone){ CWsScreenDevice screen(ws); r=screen.Construct(); if(r==KErrNone){CWindowGc gc(&screen);r=gc.Construct();if(r==KErrNone)r=run(ws,screen,gc);} ws.Close(); }
    CActiveScheduler::Install(0);delete scheduler;delete cleanup;return r;
}
