package de.voci.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import de.voci.VociViewModel
import de.voci.features.sprint.*
import de.voci.features.categories.*
import de.voci.features.library.*
import de.voci.features.player.*
import de.voci.features.recall.*
import de.voci.features.settings.*
import de.voci.ui.components.*
import de.voci.ui.theme.*

@Composable fun VociNavigation(vm:VociViewModel,nav:VociNavigator=rememberVociNavigator(vm)) {
 val entry by nav.tabs.currentBackStackEntryAsState()
 val sessionEntry by nav.sessions.currentBackStackEntryAsState()
 val dest=entry?.destination
 val reduced=LocalReducedMotion.current
 val full=nav.sessionVisible && sessionEntry?.destination?.hasRoute<SessionIdle>()==false
 val back:()->Unit={nav.popBackStack();Unit}
 val playback by vm.app.playback.state.collectAsStateWithLifecycle()
 val lifecycleOwner=LocalLifecycleOwner.current
 // Nested NavHosts must never register on the system dispatcher: subcomposition and
 // lifecycle changes make registration order unsuitable for cross-host ownership.
 val hostBackOwner=remember(lifecycleOwner) {
  object : OnBackPressedDispatcherOwner, NavigationEventDispatcherOwner {
   override val lifecycle get()=lifecycleOwner.lifecycle
   override val onBackPressedDispatcher=OnBackPressedDispatcher()
   override val navigationEventDispatcher=NavigationEventDispatcher()
  }
 }
 // Reconcile restored presentation only after the existing playback restore has settled.
 // The coordinator guards its own playlist/request ownership; navigation never starts audio.
 LaunchedEffect(nav,vm) { vm.app.playback.restore();nav.awaitReady();nav.playbackRestored() }
 // These collectors belong to the surviving root, never a hidden page/host lifecycle.
 LaunchedEffect(nav,vm,playback.ended,playback.session?.id,playback.requestId) {
  nav.awaitReady()
  nav.playbackChanged()
  vm.claimCompletion(vm.app.playback.state.value.session?.id)?.let { nav.complete(it.sessionId,it.recall) }
 }
 // Subscribe immediately; while hosts install, retain the latest request. openPlayer
 // validates it against live playback after the wait, rejecting ended/superseded IDs.
 LaunchedEffect(nav,vm) { vm.event.filter { it.startsWith("player:") }.collectLatest {
  nav.awaitReady();nav.openPlayer(it.substringAfter("player:"))
 } }
 Scaffold(bottomBar={if(!full)Column {
  PlaybackMini(vm) { id->nav.openPlayer(id) }
  NavigationBar(containerColor=VociTokens.background) {
   val labels=listOf("Sprint","Kategorien","Bibliothek","Einstellungen")
   val routes=listOf(Sprint,Categories,Library,Settings)
   routes.forEachIndexed { i,r->NavigationBarItem(colors=NavigationBarItemDefaults.colors(selectedIconColor=VociTokens.accent,selectedTextColor=VociTokens.ink,unselectedIconColor=VociTokens.secondary,unselectedTextColor=VociTokens.secondary,indicatorColor=androidx.compose.ui.graphics.Color(0xFFE9EDFF)),selected=when(i) {0->dest?.hasRoute<Sprint>()==true;1->dest?.hasRoute<Categories>()==true || dest?.hasRoute<CategoryDetail>()==true;2->dest?.hasRoute<Library>()==true || dest?.hasRoute<LessonDetail>()==true;else->dest?.hasRoute<Settings>()==true || dest?.hasRoute<Account>()==true || dest?.hasRoute<Audio>()==true || dest?.hasRoute<LocalAi>()==true || dest?.hasRoute<Storage>()==true || dest?.hasRoute<Privacy>()==true || dest?.hasRoute<Licenses>()==true || dest?.hasRoute<LicenseText>()==true || dest?.hasRoute<LicenseNotices>()==true},onClick={nav.tab(r)},icon={NavGlyph(i)},label={Text(labels[i],style=MaterialTheme.typography.labelSmall,maxLines=2,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}) }
  }
 }}) { padding->
  CompositionLocalProvider(
   LocalOnBackPressedDispatcherOwner provides hostBackOwner,
   LocalNavigationEventDispatcherOwner provides hostBackOwner,
  ) {
  Box(Modifier.fillMaxSize().padding(padding)) {
  NavigationHostVisibility(!full) {
  NavHost(nav.tabs,Sprint,Modifier.fillMaxSize(),enterTransition={val duration=if(reduced)0 else Motion.page;fadeIn(tween(duration))+slideInHorizontally { if(reduced)0 else it/14 }},exitTransition={fadeOut(tween(if(reduced)0 else Motion.page))},popEnterTransition={fadeIn(tween(if(reduced)0 else Motion.page))+slideInHorizontally { if(reduced)0 else -it/14 }},popExitTransition={fadeOut(tween(if(reduced)0 else Motion.page))}) {
   composable<Sprint> { SprintScreen(vm,{nav.navigate(Account)}) }
   composable<Categories> { CategoriesScreen(vm,{nav.navigate(CategoryDetail(it))}) }
   composable<CategoryDetail> { e->CategoryDetailScreen(e.toRoute<CategoryDetail>().categoryId,vm,back,{nav.navigate(LessonDetail(it))},{nav.tab(Sprint)}) }
   composable<Library> { LibraryScreen(vm,{nav.navigate(LessonDetail(it))}) }
   composable<LessonDetail> { e->LessonDetailScreen(e.toRoute<LessonDetail>().lessonId,vm,back) }
   composable<Settings> { SettingsScreen { when(it) {"account"->nav.navigate(Account);"audio"->nav.navigate(Audio);"storage"->nav.navigate(Storage);"licenses"->nav.navigate(Licenses);else->nav.navigate(Privacy)} } }
   composable<Account> { AccountScreen(vm,back) }
   composable<Audio> { AudioSettingsScreen(vm,back) }
   composable<LocalAi> { RetiredGenerationScreen(back) }
   composable<Storage> { StorageScreen(vm,back) }
   composable<Privacy> { PrivacyScreen(back) }
   composable<Licenses> { LicenseListScreen(back,{nav.navigate(LicenseText(it))},{nav.navigate(LicenseNotices)}) }
   composable<LicenseText> { e->LicenseTextScreen(e.toRoute<LicenseText>().licenseId,back) }
   composable<LicenseNotices> { LicenseNoticesScreen(back) }
  }
  }
  NavigationHostVisibility(full) {
   NavHost(nav.sessions,SessionIdle,Modifier.fillMaxSize(),
    enterTransition={fadeIn(tween(if(reduced)0 else Motion.player))+slideInHorizontally { if(reduced)0 else it/14 }},
    exitTransition={fadeOut(tween(if(reduced)0 else Motion.page))},
    popEnterTransition={fadeIn(tween(if(reduced)0 else Motion.page))},
    popExitTransition={fadeOut(tween(if(reduced)0 else Motion.page))}) {
    composable<SessionIdle> {}
    composable<PlayerRoute> { e->CurrentSessionPage(nav,e.id) { PlayerScreen(e.toRoute<PlayerRoute>().sessionId,vm,back,nav::audio,nav::complete) } }
    composable<Recall> { e->CurrentSessionPage(nav,e.id) { val id=e.toRoute<Recall>().sessionId;RecallScreen(id,vm,back,{nav.showResult(id)}) } }
    composable<Result> { e->CurrentSessionPage(nav,e.id) { ResultScreen(e.toRoute<Result>().sessionId,vm,back) } }
   }
  }
  }
  }
 }
 // Sole system Back owner, outside the hosts' isolated dispatcher. The
 // gesture captures the real displayed entry; a completion during it invalidates the commit.
 PredictiveBackHandler(enabled=entry!=null && sessionEntry!=null && nav.canGoBack) {
  val started=nav.currentBackStackEntry?.id
  it.collect { }
  if(started==nav.currentBackStackEntry?.id)nav.popBackStack()
 }
}

// Navigation still owns transition disposal. A popped outgoing session must not expose
// stale controls/semantics while the next session's entrance animation is running.
@Composable private fun CurrentSessionPage(nav:VociNavigator,id:String,content:@Composable ()->Unit) {
 val current by nav.sessions.currentBackStackEntryAsState()
 if(current?.id==id)content()
}

@Composable private fun PlaybackMini(vm:VociViewModel,open:(String)->Unit) {
 val p by vm.app.playback.state.collectAsStateWithLifecycle()
 MiniPlayer(p,vm.app.playback::pausePlay,{p.session?.id?.let(open)})
}
