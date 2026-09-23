package se.eliash.cartracker.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
// CarTally, sampled from the logo artwork.
val CarTallyPetrol = Color(0xFF12302E)
val CarTallyAmber = Color(0xFFF2A531)
val CarTallyIvory = Color(0xFFF6F3EC)
val CarTallyInk = Color(0xFF161B20)

// Amber for anything that has to read on white. The logo amber is only 2.1:1
// against white - too faint even for an icon - where this is 3.8:1. Still
// short of the 4.5:1 small text needs, so text uses petrol instead.
val CarTallyAmberDeep = Color(0xFFB8740C)

// Secondary text: 6.5:1 on white, 5.8:1 on ivory.
val CarTallyMuted = Color(0xFF5A5F63)
val CarTallyHairline = Color(0xFFE4DED2)
// Borders that have to be seen - a text field's edge - where the hairline is
// only a divider: 3.7:1 on ivory, 3:1 being what a control's edge needs.
val CarTallyOutline = Color(0xFF827D74)

// Dark mode, from the "lifted petrol" mockup: the night page and the tile.
val CarTallyNight = Color(0xFF0B0F12)
val CarTallyPetrolLifted = Color(0xFF1B3E3B)
// Cards, between the two: enough lift to part from the page, 1.26:1.
val CarTallyNightCard = Color(0xFF142929)
// Secondary text on dark, from the same mockup: 9:1 on a card.
val CarTallyMutedOnDark = Color(0xFFB9C4C1)
// A control's edge on dark: 4.8:1 on the page, 3.8:1 on a card.
val CarTallyOutlineOnDark = Color(0xFF748280)

// Chart series: a car's fuels, then everything else, in this order. The
// reference data-viz palette's first three slots, checked with its validator
// against the card colours here - white, and the night card - for every
// pair, colour-blind vision included. Aqua is 2.8:1 on white, under the 3:1
// a mark should have, so the chart always writes the figures out beside it.
val SeriesBlue = Color(0xFF2A78D6)
val SeriesOrange = Color(0xFFEB6834)
val SeriesAqua = Color(0xFF1BAF7A)
val SeriesBlueOnDark = Color(0xFF3987E5)
val SeriesOrangeOnDark = Color(0xFFD95926)
val SeriesAquaOnDark = Color(0xFF199E70)

// A fill-up's consumption against the one before: green for better, red for
// worse. Each is at least 4.5:1 on its cards and page, as small text needs;
// the arrow and the sign say the same, so the colour is never alone.
val TrendBetter = Color(0xFF1E7B3C)
val TrendWorse = Color(0xFFB3261E)
val TrendBetterOnDark = Color(0xFF5CC47A)
val TrendWorseOnDark = Color(0xFFF28B82)
