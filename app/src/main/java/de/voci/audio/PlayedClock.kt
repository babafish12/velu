package de.voci.audio

/** Counts consumed media, including automatic transition tails; excludes seeks and pause wall time. */
class PlayedClock {
 var totalMs:Long=0;private set
 private var index=-1
 private var position=0L
 private var playing=false
 private var speed=1f
 private var fractional=0.0
 fun reset(value:Long=0) { totalMs=value;index=-1;position=0;playing=false;fractional=0.0 }
 fun sample(item:Int,pos:Long,isPlaying:Boolean,rate:Float) {
  if(playing && item==index && pos>=position) {
   val consumed=(pos-position)/speed.toDouble()+fractional
   val whole=consumed.toLong();totalMs+=whole;fractional=consumed-whole
  }
  index=item;position=pos;playing=isPlaying;speed=rate
 }
 fun discontinuity(oldItem:Int,oldPos:Long,newItem:Int,newPos:Long) {
  sample(oldItem,oldPos,playing,speed);index=newItem;position=newPos
 }
}
