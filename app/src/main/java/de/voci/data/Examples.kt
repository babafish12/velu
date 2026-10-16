package de.voci.data
import de.voci.domain.*

object Examples {
 val categories=listOf("restaurant" to "Restaurant","travel" to "Reisen","sport" to "Sport","work" to "Arbeit","it" to "IT","daily" to "Alltag")
 val lessons=listOf(
  lesson("restaurant","Im Restaurant","it-IT", listOf(
   listOf("Un tavolo per due, per favore.","Einen Tisch für zwei, bitte.","Avete un tavolo per due?","Haben Sie einen Tisch für zwei?"),
   listOf("Il menù, per favore.","Die Speisekarte, bitte.","Posso vedere il menù?","Kann ich die Speisekarte sehen?"),
   listOf("Vorrei dell’acqua.","Ich hätte gerne Wasser.","Vorrei dell’acqua naturale.","Ich hätte gerne stilles Wasser."),
   listOf("Il conto, per favore.","Die Rechnung, bitte.","Possiamo avere il conto?","Können wir die Rechnung haben?"))),
  lesson("sport","Beim Fussball","es-ES",listOf(
   listOf("¿Quién juega hoy?","Wer spielt heute?","¿Qué equipo juega hoy?","Welche Mannschaft spielt heute?"),
   listOf("¡Buen pase!","Guter Pass!","Ha sido un buen pase.","Das war ein guter Pass."),
   listOf("¿Cuánto falta?","Wie lange noch?","¿Cuánto falta para el final?","Wie lange noch bis zum Ende?"),
   listOf("Hemos ganado.","Wir haben gewonnen.","Hoy hemos ganado el partido.","Heute haben wir das Spiel gewonnen."))),
  lesson("it","Im IT-Team","en-GB",listOf(
   listOf("Can you reproduce it?","Kannst du es reproduzieren?","Can you reproduce the error?","Kannst du den Fehler reproduzieren?"),
   listOf("I’ll check the logs.","Ich prüfe die Protokolle.","I’ll check the server logs.","Ich prüfe die Serverprotokolle."),
   listOf("The fix is ready.","Die Korrektur ist fertig.","The fix is ready for review.","Die Korrektur ist bereit zur Prüfung."),
   listOf("Let’s test it first.","Testen wir es zuerst.","Let’s test it before release.","Testen wir es vor der Veröffentlichung.")))
 )
 private fun lesson(id:String,title:String,language:String,rows:List<List<String>>):LessonContent {
  val items=rows.mapIndexed { i,r->VocabItem("$id-$i",r[0],r[1],r[2],r[3]) }
  return LessonContent("example-$id",title,language,items,items.take(2).mapIndexed { i,v->DialogueTurn("d$i",i,v.target,v.translation) },items.mapIndexed { i,v->RecognitionQuestion("q$i",v.id,v.example,listOf(v.exampleTranslation,items[(i+1)%items.size].exampleTranslation),0) },true)
 }
 fun category(lesson:LessonContent)=lesson.id.removePrefix("example-")
}
