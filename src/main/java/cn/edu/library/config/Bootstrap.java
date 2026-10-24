package cn.edu.library.config;

import cn.edu.library.mapper.LibraryMapper;
import cn.edu.library.model.Models.*;
import cn.edu.library.service.LibraryService;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Bootstrap implements ApplicationRunner {

    private final LibraryMapper db;
    private final LibraryService service;
    private final PasswordEncoder passwords;

    @Value("${library.seed-demo:false}")
    boolean demo;

    public Bootstrap(
        LibraryMapper db,
        LibraryService service,
        PasswordEncoder passwords
    ) {
        this.db = db;
        this.service = service;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.write(() -> {
            if (db.rules().isEmpty()) {
                Rule q = new Rule();
                q.effectiveAt = LocalDateTime.of(2020, 1, 1, 0, 0);
                q.advanceDays = 2;
                q.maxDurationMinutes = 240;
                q.checkinEarlyMinutes = 10;
                q.checkinGraceMinutes = 15;
                q.leaveMinutes = 30;
                q.leaveLimit = 2;
                q.violationWindowDays = 7;
                q.violationLimit = 3;
                q.blockDays = 3;
                db.insertRule(q);
            }
            if (demo && db.users("").isEmpty()) {
                User admin = new User();
                admin.studentNo = "admin";
                admin.name = "图书馆管理员";
                admin.className = "管理中心";
                admin.role = "ADMIN";
                admin.enabled = true;
                admin.passwordHash = passwords.encode("Admin123!");
                db.insertUser(admin);
                String hash = passwords.encode("Student123!");
                for (int i = 1; i <= 20; i++) {
                    User u = new User();
                    u.studentNo = String.format("202600%02d", i);
                    u.name = "演示学生" + String.format("%02d", i);
                    u.className = "软件技术 1 班";
                    u.role = "STUDENT";
                    u.enabled = true;
                    u.passwordHash = hash;
                    db.insertUser(u);
                }
                String[] names = { "综合阅览室", "静音自习室", "电子阅览室" };
                String[] notes = {
                    "自然采光充足，适合日常阅读与自习。",
                    "请保持安静，共同维护专注的学习环境。",
                    "提供电源插座，适合使用个人笔记本电脑。",
                };
                for (int i = 0; i < 3; i++) {
                    Room r = new Room();
                    r.code = "R" + (i + 1);
                    r.name = names[i];
                    r.floor = i + 2 + "F";
                    r.description = notes[i];
                    r.enabled = true;
                    r.openTime = LocalTime.of(8, 0);
                    r.closeTime = LocalTime.of(22, 0);
                    db.insertRoom(r);
                    for (int row = 1; row <= 5; row++) for (
                        int col = 1;
                        col <= 8;
                        col++
                    ) {
                        Seat s = new Seat();
                        s.roomId = r.id;
                        s.seatNo = String.format(
                            "%c-%02d",
                            'A' + i,
                            (row - 1) * 8 + col
                        );
                        s.rowNo = row;
                        s.colNo = col;
                        s.enabled = true;
                        s.hasSocket = i == 2 || col % 2 == 0;
                        s.nearWindow = col == 1 || col == 8;
                        db.insertSeat(s);
                    }
                }
                Notice n = new Notice();
                n.title = "欢迎使用图书馆座位预约系统";
                n.content =
                    "请选择日期与时段后预约座位。请按时签到，离馆前记得签退，将座位留给有需要的同学。当前开放时间与预约参数为演示规则。";
                n.published = true;
                n.createdBy = admin.id;
                db.insertNotice(n);
                Notice n2 = new Notice();
                n2.title = "文明自习，共享安静空间";
                n2.content =
                    "请勿用物品长期占座。暂离时使用系统登记，返回后及时确认；超时未返回会释放座位并记录违约。";
                n2.published = true;
                n2.createdBy = admin.id;
                db.insertNotice(n2);
            }
            return null;
        });
        service.sweep();
    }
}
