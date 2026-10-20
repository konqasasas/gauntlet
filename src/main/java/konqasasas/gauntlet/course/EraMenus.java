package konqasasas.gauntlet.course;

import java.io.IOException;
import java.util.*;
import java.util.function.UnaryOperator;
import konqasasas.gauntlet.i18n.Msg;
import konqasasas.gauntlet.ui.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Eras (時代): the builders' list and editor (name, box, courses in order, lanes),
 * and the players' screen for choosing where to start (/course era <id>).
 */
final class EraMenus {
    private final CourseEditor editor;
    EraMenus(CourseEditor editor) { this.editor=editor; }
    private EraStore eras() { return editor.spaces.eras(); }

    // ---- Builders ----
    void openList(Player player) {
        if(!editor.admin(player)) return;
        Menu menu=new Menu(6,Msg.plain(player,"editor.eras.title"));
        List<Era> all=eras().all();
        for(int i=0;i<Math.min(all.size(),45);i++) {
            Era era=all.get(i);
            List<Component> lore=new ArrayList<>(List.of(
                    Msg.text(player,"editor.list.course.id",Msg.arg("id",era.id())),
                    Msg.text(player,"editor.era.counts",Msg.arg("courses",era.courses().size()),Msg.arg("lanes",era.space().laneCount()))));
            problems(player,era).forEach(p -> lore.add(Msg.text(player,"editor.warning",Msg.arg("text",p))));
            ItemStack icon=new ItemStack(Material.CLOCK);
            icon.editMeta(meta -> { meta.displayName(Icons.text(era.name())); meta.lore(lore); });
            menu.button(i,icon,e -> openEra(player,era.id()));
        }
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back-to-list"),e -> editor.openList(player));
        menu.button(49,Msg.icon(player,Material.WRITABLE_BOOK,"editor.eras.create"),e -> askNewId(player));
        menu.open(player);
    }
    private void askNewId(Player player) {
        editor.prompts.ask(player,"editor.prompt.era-id",text -> {
            if(!editor.admin(player)) return;
            String id=text.toLowerCase(Locale.ROOT);
            if(!CourseStore.validId(id) || eras().get(id)!=null) { editor.tell(player,"editor.new.invalid-id",Msg.arg("text",text)); return; }
            Selections.Selected selected=editor.selection(player);
            Era era=selected==null ? Era.create(id,player.getWorld().getUID(),null) : Era.create(id,selected.world(),selected.region());
            try { eras().save(era); }
            catch(IOException|RuntimeException e) { editor.failure(player,"editor.era.save-failed",e,Msg.arg("id",id)); return; }
            editor.tell(player,"editor.era.created",Msg.arg("id",id));
            openEra(player,id);
        });
    }
    /** Problems that keep the era's courses from being played, as text. */
    List<String> problems(Player player,Era era) {
        List<String> problems=new ArrayList<>();
        if(era.region()==null) problems.add(Msg.plain(player,"problem.no-region"));
        if(era.space().lanesOverlap()) problems.add(Msg.plain(player,"problem.lane-overlap"));
        for(String id:era.courses()) {
            Course c=editor.store.get(id);
            if(c==null) problems.add(Msg.plain(player,"editor.era.course-missing",Msg.arg("id",id)));
            else if(!c.world().equals(era.world()) || (era.region()!=null && c.region()!=null && !era.region().contains(c.region())))
                problems.add(Msg.plain(player,"editor.era.course-outside-named",Msg.arg("name",c.name())));
        }
        return problems;
    }
    private boolean save(Player player,String id,UnaryOperator<Era> change) {
        if(!editor.admin(player)) return false;
        Era era=eras().get(id);
        if(era==null) { editor.tell(player,"editor.era.deleted",Msg.arg("id",id)); return false; }
        try { eras().save(change.apply(era)); return true; }
        catch(IOException|RuntimeException e) { editor.failure(player,"editor.era.save-failed",e,Msg.arg("id",id)); return false; }
    }
    void openEra(Player player,String id) {
        Era era=eras().get(id);
        if(era==null) { editor.tell(player,"editor.era.deleted",Msg.arg("id",id)); openList(player); return; }
        Menu menu=new Menu(4,Msg.plain(player,"editor.era.title",Msg.arg("name",era.name())));
        List<Component> info=new ArrayList<>(List.of(
                Msg.text(player,"editor.list.course.id",Msg.arg("id",era.id())),
                Msg.text(player,"editor.era.counts",Msg.arg("courses",era.courses().size()),Msg.arg("lanes",era.space().laneCount()))));
        problems(player,era).forEach(p -> info.add(Msg.text(player,"editor.warning",Msg.arg("text",p))));
        ItemStack book=new ItemStack(Material.BOOK);
        book.editMeta(meta -> { meta.displayName(Icons.text(era.name())); meta.lore(info); });
        menu.label(4,book);
        menu.button(10,Msg.icon(player,Material.NAME_TAG,"editor.edit.name",Msg.arg("current",era.name())),
                e -> editor.prompts.ask(player,"editor.prompt.name",text -> {
                    if(text.isBlank() || text.length()>Course.MAX_NAME_LENGTH) { editor.tell(player,"editor.name-length",Msg.arg("max",Course.MAX_NAME_LENGTH)); return; }
                    if(save(player,id,x -> x.withName(text))) openEra(player,id);
                },Msg.arg("max",Course.MAX_NAME_LENGTH)));
        menu.button(11,Msg.icon(player,Material.MAP,"editor.era.region",
                Msg.arg("current",era.region()==null ? Msg.text(player,"editor.unset") : Component.text(era.region().describe()))),e -> {
            Selections.Selected selected=editor.selection(player);
            if(selected==null) { editor.tell(player,"editor.region.no-selection"); return; }
            if(editor.lanes.busy(era.space())) { editor.tell(player,"editor.era.busy"); return; }
            if(!save(player,id,x -> x.withRegion(selected.world(),selected.region()))) return;
            editor.tell(player,"editor.region.done",Msg.arg("region",selected.region().describe()));
            if(!era.lanes().isEmpty()) editor.tell(player,"editor.lanes.region-changed");
            openEra(player,id);
        });
        menu.button(12,Msg.icon(player,Material.REPEATER,"editor.era.courses",Msg.arg("count",era.courses().size())),e -> openCourses(player,id));
        menu.button(13,Msg.icon(player,Material.RAIL,"editor.edit.lanes",Msg.arg("count",era.space().laneCount()),Msg.arg("waiting",editor.lanes.waitingFor(era.space()))),
                e -> editor.openLanes(player,era.space().key(),() -> openEra(player,id)));
        menu.button(14,Msg.icon(player,Material.NETHER_STAR,"editor.era.start-screen"),e -> openStart(player,id));
        Course boss=era.boss()==null ? null : editor.store.get(era.boss());
        menu.button(19,Msg.icon(player,Material.WITHER_SKELETON_SKULL,"editor.era.boss",
                Msg.arg("course",boss==null ? Msg.text(player,"editor.none") : Component.text(boss.name())),Msg.arg("rank",era.bossRank())),e -> {
            if(e.isLeftClick() && !e.isShiftClick()) {
                editor.prompts.ask(player,"editor.prompt.era-boss",text -> {
                    Era now=eras().get(id); if(now==null) return;
                    String courseId=text.strip().toLowerCase(Locale.ROOT);
                    if(courseId.equals("-")) { if(save(player,id,x -> x.withBoss(null,x.bossRank()))) openEra(player,id); return; }
                    if(!now.courses().contains(courseId)) { editor.tell(player,"editor.era.boss-not-in-era",Msg.arg("id",courseId)); return; }
                    // A boss without a rank yet gets the era's own number (era I's boss gives rank 1).
                    int rank=now.bossRank()>0 ? now.bossRank() : Math.min(Era.MAX_RANK,now.requiredRank()+1);
                    if(save(player,id,x -> x.withBoss(courseId,rank))) openEra(player,id);
                });
                return;
            }
            int delta=e.isShiftClick() ? -1 : 1;
            if(save(player,id,x -> x.withBoss(x.boss(),x.bossRank()+delta))) openEra(player,id);
        });
        menu.button(20,Msg.icon(player,Material.IRON_BARS,"editor.era.required-rank",Msg.arg("rank",era.requiredRank())),e -> {
            int delta=e.isRightClick() ? -1 : 1;
            if(save(player,id,x -> x.withRequiredRank(x.requiredRank()+delta))) openEra(player,id);
        });
        menu.button(27,Msg.icon(player,Material.ARROW,"editor.back"),e -> openList(player));
        menu.button(35,Msg.icon(player,Material.BARRIER,"editor.era.delete"),e -> {
            if(!e.isShiftClick()) { editor.tell(player,"editor.era.delete-shift"); return; }
            if(!editor.admin(player)) return;
            if(editor.lanes.busy(era.space())) { editor.tell(player,"editor.era.busy"); return; }
            try { eras().delete(id); editor.tell(player,"editor.era.deleted-done",Msg.arg("id",id)); }
            catch(IOException ex) { editor.failure(player,"editor.era.save-failed",ex,Msg.arg("id",id)); }
            openList(player);
        });
        menu.open(player);
    }
    /** The courses in order. Click: move up. Shift+right: take out. */
    private void openCourses(Player player,String id) {
        Era era=eras().get(id);
        if(era==null) { openList(player); return; }
        Menu menu=new Menu(6,Msg.plain(player,"editor.era.courses-title",Msg.arg("name",era.name())));
        for(int i=0;i<Math.min(era.courses().size(),45);i++) {
            int index=i; String courseId=era.courses().get(i);
            Course c=editor.store.get(courseId);
            ItemStack icon=Msg.icon(player,c==null ? Material.BARRIER : Material.FILLED_MAP,"editor.era.course-entry",
                    Msg.arg("index",i+1),Msg.arg("name",c==null ? courseId : c.name()),Msg.arg("id",courseId));
            menu.button(i,icon,e -> {
                if(e.isShiftClick() && e.isRightClick()) {
                    if(editor.lanes.busy(era.space())) { editor.tell(player,"editor.era.busy"); return; }
                    List<String> next=new ArrayList<>(era.courses()); next.remove(courseId);
                    if(save(player,id,x -> x.withCourses(next))) { editor.tell(player,"editor.era.course-removed",Msg.arg("id",courseId)); openCourses(player,id); }
                    return;
                }
                if(index==0) return;
                List<String> next=new ArrayList<>(era.courses()); next.add(index-1,next.remove(index));
                if(save(player,id,x -> x.withCourses(next))) openCourses(player,id);
            });
        }
        menu.button(45,Msg.icon(player,Material.ARROW,"editor.back"),e -> openEra(player,id));
        menu.button(49,Msg.icon(player,Material.LIME_DYE,"editor.era.course-add"),e -> editor.prompts.ask(player,"editor.prompt.era-course",text -> {
            Era now=eras().get(id); if(now==null) return;
            String courseId=text.toLowerCase(Locale.ROOT);
            Course c=editor.store.get(courseId);
            if(c==null) { editor.tell(player,"editor.deleted",Msg.arg("id",courseId)); return; }
            if(!c.world().equals(now.world())) { editor.tell(player,"editor.era.other-world"); return; }
            // Lanes belong to the era: a course with lanes of its own must drop them first.
            if(!c.lanes().isEmpty()) { editor.tell(player,"editor.era.course-has-lanes",Msg.arg("name",c.name())); return; }
            if(editor.lanes.busy(now.space()) || editor.lanes.busy(c.space())) { editor.tell(player,"editor.era.busy"); return; }
            Era before=eras().of(courseId);
            List<String> next=new ArrayList<>(now.courses()); if(!next.contains(courseId)) next.add(courseId);
            if(!save(player,id,x -> x.withCourses(next))) return;
            editor.tell(player,before!=null && !before.id().equals(id) ? "editor.era.course-moved" : "editor.era.course-added",
                    Msg.arg("name",c.name()),Msg.arg("from",before==null ? "" : before.name()));
            if(now.region()!=null && c.region()!=null && !now.region().contains(c.region())) editor.tell(player,"editor.era.course-outside");
            openCourses(player,id);
        }));
        menu.label(53,Msg.icon(player,Material.BOOK,"editor.era.courses-help"));
        menu.open(player);
    }

    // ---- Players: where to start ----
    /** The era's courses in order; reached ones (and the first) can be started. Left: official, right: practice. */
    void openStart(Player player,String id) {
        Era era=eras().get(id);
        if(era==null) { Msg.send(player,"play.era.not-found",Msg.arg("id",id)); return; }
        Menu menu=new Menu(6,Msg.plain(player,"play.era.title",Msg.arg("name",era.name())));
        int slot=0;
        for(String courseId:era.courses()) {
            if(slot>=54) break;
            Course c=editor.store.get(courseId);
            if(c==null || (!player.hasPermission(CourseEditor.ADMIN) && c.status()!=CourseStatus.DONE)) continue;
            boolean open=editor.participants.mayStart(player,c);
            ItemStack icon=Msg.icon(player,open ? Material.FILLED_MAP : Material.GRAY_DYE,open ? "play.era.course" : "play.era.course-locked",
                    Msg.arg("index",slot+1),Msg.arg("name",c.name()));
            if(open) menu.button(slot,icon,e -> editor.participants.enter(player,courseId,e.isRightClick()));
            else menu.label(slot,icon);
            slot++;
        }
        menu.open(player);
    }
}
