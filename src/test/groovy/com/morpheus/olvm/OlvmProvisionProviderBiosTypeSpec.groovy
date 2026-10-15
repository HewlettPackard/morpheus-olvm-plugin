package com.morpheus.olvm

import com.morpheusdata.model.VirtualImage
import spock.lang.Specification
import spock.lang.Unroll

class OlvmProvisionProviderBiosTypeSpec extends Specification {

	OlvmProvisionProvider provider = new OlvmProvisionProvider(null, null)

	@Unroll
	def "resolveBiosType returns #expected for #description"() {
		expect:
		provider.resolveBiosType(image) == expected

		where:
		description                          | image                                                                            | expected
		'a secure boot image'                | new VirtualImage(secureBoot: true, uefi: true)                                   | 'q35_secure_boot'
		'a UEFI image'                       | new VirtualImage(uefi: true)                                                     | 'q35_ovmf'
		'an uploaded BIOS image'             | new VirtualImage(code: 'morpheus.ubuntu.22.04')                                  | 'q35_sea_bios'
		'an image with no code'              | new VirtualImage()                                                               | 'q35_sea_bios'
		'no image'                           | null                                                                             | 'q35_sea_bios'
		'a synced OLVM template'             | new VirtualImage(code: 'olvm.plugin.template.70.abc')                            | null
		'a synced UEFI OLVM template'        | new VirtualImage(code: 'olvm.plugin.template.70.abc', uefi: true)                | 'q35_ovmf'
	}

	def "applyBiosType keeps an explicit biosType override"() {
		when:
		def config = provider.applyBiosType([biosType: 'q35_ovmf', other: 'x'], new VirtualImage())

		then:
		config.biosType == 'q35_ovmf'
		config.other == 'x'
	}

	def "applyBiosType does not mutate the source config"() {
		given:
		def source = [other: 'x']

		when:
		def config = provider.applyBiosType(source, new VirtualImage())

		then:
		config.biosType == 'q35_sea_bios'
		!source.containsKey('biosType')
	}

	def "applyBiosType handles a null config"() {
		expect:
		provider.applyBiosType(null, new VirtualImage(uefi: true)) == [biosType: 'q35_ovmf']
	}
}
