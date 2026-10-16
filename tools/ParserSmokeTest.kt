package ru.nspu.schedule

import java.time.LocalDate

/** Representative NSPU text excerpt (not a claim of an up-to-date saved timetable). */
fun main() {
    val source = """
       Приложение НГПУ Расписание Расписание звонков 1-я пара: 8.30 – 10.00;
       >> ИФМИТО >> Выбор группы >> 3.008.2.26 ИСиТвО
       Группа 3.008.2.26 ИСиТвО (09.03.02 Информационные системы и технологии)
       3.008.2.26 Уважаемые студенты! Сегодня, 05.10.2026 г., занятие по дисциплине
       "Иностранный язык" в подгруппе А9 будет проходить в 106 ауд.
       1 семестр Теоретическое обучение с 01.09.2026 г. по 17.12.2026 г.
       ← Числитель, текущая неделя номер 7 Знаменатель →
       Понедельник 8:30-10:00 Русский язык и культура речи, И.Н.Азарова, (л.: 2,6-9 нед. *), а. 206
       10:10-11:40 Русский язык и культура речи, И.Н.Азарова, (п.з.: 6-10 нед. *), а. 206
       12:10-13:40 Иностранный язык, В.А.Видеркер, (л.з.: 3-14 нед. *), а. 216, А7;
       Иностранный язык, Ш.А.Норов, (л.з.: 3-14 нед. *), а. 402, А8;
       Иностранный язык, С.С.Мензатова, (л.з.: 3-14 нед. *), а. 412, А9
       14:10-15:40 Безопасность жизнедеятельности, О.Л.Жигарев, (п.з.: 2-6 нед. *), а. 206
       Вторник 10:10-11:40 История России, А.А.Ларочкин, (п.з.: 1-15 нед. *), а. 314
       12:10-13:40 Основы российской государственности, Н.В.Виниченко, (п.з.: 1-16 нед. *), а. 314
       14:10-15:40 Профессиональная этика и этикет, А.А.Астахова, (п.з.: 7-11 нед. *), а. 406
       Среда 8:30-10:00 Математика в профильной подготовке, А.А.Варжавин, (п.з.: 6-10 нед. *), а. 404
       10:10-11:40 Математика в профильной подготовке, А.А.Варжавин, (п.з.: 1-10 нед. *), а. 404
       12:10-13:40 Начертательная геометрия и компьютерная инженерная графика, И.Н.Лукина, (л.: 6-10 нед. *), а. 417
       Четверг 10:10-11:40 Безопасность жизнедеятельности, О.Л.Жигарев, (л.: 1-6 нед. *), а. 2п
       12:10-13:40 Иностранный язык, Ш.А.Норов, (л.з.: 3-14 нед. *), а. 402, А3
       Пятница 8:30-10:00 Сервис информационных систем, В.Ю.Верещагин, (л.: 7-10 нед. *), а. 213
       10:10-11:40 Сервис информационных систем, В.Ю.Верещагин, (п.з.: 7-12 нед. *), а. 411
       12:10-13:40 Физическая культура и спорт, Е.В.Шестаев, (п.з.: 5-15 нед. *), а. Спортзал #1
       14:10-15:40 История России, В.И.Баяндин, (л.: 1-14 нед. *), а. 1п
       15:50-17:20 Основы российской государственности, Н.В.Виниченко, (л.: 1-9 нед. *), а. 3п
       Суббота 10:10-11:40 Математика в профильной подготовке, А.А.Варжавин, а. 404, 28.11.2026 г. - зачёт
       Последнее изменение расписания - 15.10.2026
    """.trimIndent()
    val snapshot = SiteParser.parse(source)
    check(snapshot.notices.size == 1)
    check(snapshot.notices.first().published == null)
    check(snapshot.notices.first().text.contains("106 ауд."))
    check(!SiteParser.noticeIsOutdated(snapshot.notices.first(), LocalDate.of(2026, 10, 16)))
    check(SiteParser.noticeLabel(snapshot.notices.first()) == "ОБЪЯВЛЕНИЕ НГПУ")
    check(SiteParser.subgroupFits(Lesson(0, "12:10-13:40", "Иностранный язык, А7"), "Все"))
    check(snapshot.lessons.size >= 17)
    val mondayEnglish = snapshot.lessons.filter { it.day == 0 && it.time == "12:10-13:40" }
    check(mondayEnglish.size == 3)
    check(mondayEnglish.count { SiteParser.subgroupFits(it.raw, "А7") } == 1)
    val monday9 = LocalDate.of(2026, 10, 12)
    check(mondayEnglish.count { SiteParser.applicable(it, monday9) } == 3)
    val olderMon = LocalDate.of(2026, 9, 7)
    check(mondayEnglish.none { SiteParser.applicable(it, olderMon) })
    check(snapshot.lessons.count { it.day == 4 && SiteParser.applicable(it, LocalDate.of(2026, 10, 16)) } == 5)
    check(SiteParser.shortKind("ЛЕКЦИЯ") == "л.")
    check(SiteParser.shortKind("ПРАКТИКА") == "п.з.")
    check(SiteParser.shortKind("ЛАБОРАТОРНАЯ") == "л.з.")
    check(SiteParser.shortKind("ЗАЧЁТ") == "зач.")
    check(SiteParser.shortKind("ЭКЗАМЕН") == "экз.")
    check(SiteParser.lessonKind(
      Lesson(4,"08:30-10:00","Сервис, Преподаватель, (л.: 7-10 нед. *), а. 213"),
      LocalDate.of(2026,10,16)) == "ЛЕКЦИЯ")
    check(SiteParser.lessonKind(
      Lesson(4,"10:10-11:40","Сервис, Преподаватель, (п.з.: 7-12 нед. *), а. 411"),
      LocalDate.of(2026,10,16)) == "ПРАКТИКА")
    val saturday = snapshot.lessons.first { it.day == 5 }
    check(!SiteParser.applicable(saturday, LocalDate.of(2026, 10, 17)))
    check(SiteParser.applicable(saturday, LocalDate.of(2026, 11, 28)))
    println("PASS: ${snapshot.lessons.size} lessons, ${snapshot.notices.size} notice, subgroups, weeks and dated exam")
}
